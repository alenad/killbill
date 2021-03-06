/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014-2016 Groupon, Inc
 * Copyright 2014-2016 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.entitlement.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.ObjectType;
import org.killbill.billing.account.api.AccountInternalApi;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.callcontext.InternalTenantContext;
import org.killbill.billing.catalog.api.BillingActionPolicy;
import org.killbill.billing.catalog.api.PlanPhasePriceOverride;
import org.killbill.billing.catalog.api.PlanPhaseSpecifier;
import org.killbill.billing.catalog.api.ProductCategory;
import org.killbill.billing.entitlement.AccountEventsStreams;
import org.killbill.billing.entitlement.EntitlementService;
import org.killbill.billing.entitlement.EventsStream;
import org.killbill.billing.entitlement.api.EntitlementPluginExecution.WithEntitlementPlugin;
import org.killbill.billing.entitlement.api.svcs.DefaultEntitlementApiBase;
import org.killbill.billing.entitlement.block.BlockingChecker;
import org.killbill.billing.entitlement.dao.BlockingStateDao;
import org.killbill.billing.entitlement.engine.core.EntitlementUtils;
import org.killbill.billing.entitlement.engine.core.EventsStreamBuilder;
import org.killbill.billing.entitlement.plugin.api.EntitlementContext;
import org.killbill.billing.entitlement.plugin.api.OperationType;
import org.killbill.billing.junction.DefaultBlockingState;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.security.api.SecurityApi;
import org.killbill.billing.subscription.api.SubscriptionBase;
import org.killbill.billing.subscription.api.SubscriptionBaseInternalApi;
import org.killbill.billing.subscription.api.transfer.SubscriptionBaseTransferApi;
import org.killbill.billing.subscription.api.transfer.SubscriptionBaseTransferApiException;
import org.killbill.billing.subscription.api.user.SubscriptionBaseApiException;
import org.killbill.billing.subscription.api.user.SubscriptionBaseBundle;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.InternalCallContextFactory;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.bus.api.PersistentBus;
import org.killbill.clock.Clock;
import org.killbill.notificationq.api.NotificationQueueService;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

public class DefaultEntitlementApi extends DefaultEntitlementApiBase implements EntitlementApi {

    public static final String ENT_STATE_BLOCKED = "ENT_BLOCKED";
    public static final String ENT_STATE_CLEAR = "ENT_CLEAR";
    public static final String ENT_STATE_CANCELLED = "ENT_CANCELLED";

    private final SubscriptionBaseInternalApi subscriptionBaseInternalApi;
    private final SubscriptionBaseTransferApi subscriptionBaseTransferApi;
    private final Clock clock;
    private final InternalCallContextFactory internalCallContextFactory;
    private final BlockingChecker checker;
    private final BlockingStateDao blockingStateDao;
    private final EntitlementDateHelper dateHelper;
    private final PersistentBus eventBus;
    private final EventsStreamBuilder eventsStreamBuilder;
    private final EntitlementUtils entitlementUtils;
    private final NotificationQueueService notificationQueueService;
    private final EntitlementPluginExecution pluginExecution;
    private final SecurityApi securityApi;

    @Inject
    public DefaultEntitlementApi(final PersistentBus eventBus, final InternalCallContextFactory internalCallContextFactory,
                                 final SubscriptionBaseTransferApi subscriptionTransferApi, final SubscriptionBaseInternalApi subscriptionInternalApi,
                                 final AccountInternalApi accountApi, final BlockingStateDao blockingStateDao, final Clock clock,
                                 final BlockingChecker checker, final NotificationQueueService notificationQueueService,
                                 final EventsStreamBuilder eventsStreamBuilder, final EntitlementUtils entitlementUtils,
                                 final EntitlementPluginExecution pluginExecution,
                                 final SecurityApi securityApi) {
        super(eventBus, null, pluginExecution, internalCallContextFactory, subscriptionInternalApi, accountApi, blockingStateDao, clock, checker, notificationQueueService, eventsStreamBuilder, entitlementUtils, securityApi);
        this.eventBus = eventBus;
        this.internalCallContextFactory = internalCallContextFactory;
        this.subscriptionBaseInternalApi = subscriptionInternalApi;
        this.subscriptionBaseTransferApi = subscriptionTransferApi;
        this.clock = clock;
        this.checker = checker;
        this.blockingStateDao = blockingStateDao;
        this.notificationQueueService = notificationQueueService;
        this.eventsStreamBuilder = eventsStreamBuilder;
        this.entitlementUtils = entitlementUtils;
        this.pluginExecution = pluginExecution;
        this.securityApi = securityApi;
        this.dateHelper = new EntitlementDateHelper(accountApi, clock);
    }

    @Override
    public Entitlement createBaseEntitlement(final UUID accountId, final PlanPhaseSpecifier planPhaseSpecifier, final String externalKey, final List<PlanPhasePriceOverride> overrides, final LocalDate effectiveDate, final Iterable<PluginProperty> properties, final CallContext callContext) throws EntitlementApiException {

        final EntitlementSpecifier entitlementSpecifier = new DefaultEntitlementSpecifier(planPhaseSpecifier, overrides);
        final List<EntitlementSpecifier> entitlementSpecifierList = new ArrayList<EntitlementSpecifier>();
        entitlementSpecifierList.add(entitlementSpecifier);
        final EntitlementContext pluginContext = new DefaultEntitlementContext(OperationType.CREATE_SUBSCRIPTION,
                                                                               accountId,
                                                                               null,
                                                                               null,
                                                                               externalKey,
                                                                               entitlementSpecifierList,
                                                                               effectiveDate,
                                                                               properties,
                                                                               callContext);

        final WithEntitlementPlugin<Entitlement> createBaseEntitlementWithPlugin = new WithEntitlementPlugin<Entitlement>() {
            @Override
            public Entitlement doCall(final EntitlementApi entitlementApi, final EntitlementContext updatedPluginContext) throws EntitlementApiException {
                final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(accountId, callContext);
                try {

                    if (entitlementUtils.getFirstActiveSubscriptionIdForKeyOrNull(externalKey, contextWithValidAccountRecordId) != null) {
                        throw new EntitlementApiException(new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_ACTIVE_BUNDLE_KEY_EXISTS, externalKey));
                    }

                    final SubscriptionBaseBundle bundle = subscriptionBaseInternalApi.createBundleForAccount(accountId, externalKey, contextWithValidAccountRecordId);

                    final DateTime referenceTime = clock.getUTCNow();
                    final DateTime requestedDate = dateHelper.fromLocalDateAndReferenceTime(updatedPluginContext.getEffectiveDate(), referenceTime, contextWithValidAccountRecordId);
                    final EntitlementSpecifier specifier = getFirstEntitlementSpecifier(updatedPluginContext.getEntitlementSpecifiers());
                    final SubscriptionBase subscription = subscriptionBaseInternalApi.createSubscription(bundle.getId(), specifier.getPlanPhaseSpecifier(), specifier.getOverrides(), requestedDate, contextWithValidAccountRecordId);

                    return new DefaultEntitlement(subscription.getId(), eventsStreamBuilder, entitlementApi, pluginExecution,
                                                  blockingStateDao, subscriptionBaseInternalApi, checker, notificationQueueService,
                                                  entitlementUtils, dateHelper, clock, securityApi, internalCallContextFactory, callContext);
                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e);
                }
            }
        };
        return pluginExecution.executeWithPlugin(createBaseEntitlementWithPlugin, pluginContext);
    }

    private EntitlementSpecifier getFirstEntitlementSpecifier(final List<EntitlementSpecifier> entitlementSpecifiers) throws SubscriptionBaseApiException {
        if ((entitlementSpecifiers == null) || entitlementSpecifiers.isEmpty()) {
            throw new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_INVALID_ENTITLEMENT_SPECIFIER);
        }
        return entitlementSpecifiers.get(0);
    }

    @Override
    public Entitlement createBaseEntitlementWithAddOns(final UUID accountId, final String externalKey, final Iterable<EntitlementSpecifier> entitlementSpecifiers,
                                                       final LocalDate effectiveDate, final Iterable<PluginProperty> properties, final CallContext callContext)
            throws EntitlementApiException {

        final EntitlementSpecifier baseSpecifier = Iterables.tryFind(entitlementSpecifiers, new Predicate<EntitlementSpecifier>() {
            @Override
            public boolean apply(final EntitlementSpecifier specifier) {
                return specifier.getPlanPhaseSpecifier() != null && ProductCategory.BASE.equals(specifier.getPlanPhaseSpecifier().getProductCategory());
            }
        }).orNull();

        if (baseSpecifier == null) {
            throw new EntitlementApiException(new IllegalArgumentException(), ErrorCode.SUB_CREATE_NO_BP.getCode(), "Missing Base Subscription.");
        }

        final List<EntitlementSpecifier> entitlementSpecifierList = new ArrayList<EntitlementSpecifier>();
        Iterables.addAll(entitlementSpecifierList, entitlementSpecifiers);

        final EntitlementContext pluginContext = new DefaultEntitlementContext(OperationType.CREATE_SUBSCRIPTIONS_WITH_AO,
                                                                               accountId,
                                                                               null,
                                                                               null,
                                                                               externalKey,
                                                                               entitlementSpecifierList,
                                                                               effectiveDate,
                                                                               properties,
                                                                               callContext);

        final WithEntitlementPlugin<Entitlement> createBaseEntitlementWithAddOn = new WithEntitlementPlugin<Entitlement>() {
            @Override
            public Entitlement doCall(final EntitlementApi entitlementApi, final EntitlementContext updatedPluginContext) throws EntitlementApiException {
                final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(accountId, callContext);

                try {
                    if (entitlementUtils.getFirstActiveSubscriptionIdForKeyOrNull(externalKey, contextWithValidAccountRecordId) != null) {
                        throw new EntitlementApiException(new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_ACTIVE_BUNDLE_KEY_EXISTS, externalKey));
                    }

                    final SubscriptionBaseBundle bundle = subscriptionBaseInternalApi.createBundleForAccount(accountId, externalKey, contextWithValidAccountRecordId);

                    final DateTime referenceTime = clock.getUTCNow();
                    final DateTime requestedDate = dateHelper.fromLocalDateAndReferenceTime(updatedPluginContext.getEffectiveDate(), referenceTime, contextWithValidAccountRecordId);
                    final SubscriptionBase subscription = subscriptionBaseInternalApi.createBaseSubscriptionWithAddOns(bundle.getId(), entitlementSpecifiers, requestedDate, contextWithValidAccountRecordId);

                    return new DefaultEntitlement(subscription.getId(), eventsStreamBuilder, entitlementApi, pluginExecution,
                                                  blockingStateDao, subscriptionBaseInternalApi, checker, notificationQueueService,
                                                  entitlementUtils, dateHelper, clock, securityApi, internalCallContextFactory, callContext);

                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e);
                }

            }
        };
        return pluginExecution.executeWithPlugin(createBaseEntitlementWithAddOn, pluginContext);
    }

    @Override
    public Entitlement addEntitlement(final UUID bundleId, final PlanPhaseSpecifier planPhaseSpecifier, final List<PlanPhasePriceOverride> overrides, final LocalDate effectiveDate, final Iterable<PluginProperty> properties, final CallContext callContext) throws EntitlementApiException {

        final EntitlementSpecifier entitlementSpecifier = new DefaultEntitlementSpecifier(planPhaseSpecifier, overrides);
        final List<EntitlementSpecifier> entitlementSpecifierList = new ArrayList<EntitlementSpecifier>();
        entitlementSpecifierList.add(entitlementSpecifier);
        final EntitlementContext pluginContext = new DefaultEntitlementContext(OperationType.CREATE_SUBSCRIPTION,
                                                                               null,
                                                                               null,
                                                                               bundleId,
                                                                               null,
                                                                               entitlementSpecifierList,
                                                                               effectiveDate,
                                                                               properties,
                                                                               callContext);

        final WithEntitlementPlugin<Entitlement> addEntitlementWithPlugin = new WithEntitlementPlugin<Entitlement>() {
            @Override
            public Entitlement doCall(final EntitlementApi entitlementApi, final EntitlementContext updatedPluginContext) throws EntitlementApiException {
                final EventsStream eventsStreamForBaseSubscription = eventsStreamBuilder.buildForBaseSubscription(bundleId, callContext);

                // Check the base entitlement state is active
                if (!eventsStreamForBaseSubscription.isEntitlementActive()) {
                    throw new EntitlementApiException(ErrorCode.SUB_GET_NO_SUCH_BASE_SUBSCRIPTION, bundleId);
                }

                // Check the base entitlement state is not blocked
                if (eventsStreamForBaseSubscription.isBlockChange()) {
                    throw new EntitlementApiException(new BlockingApiException(ErrorCode.BLOCK_BLOCKED_ACTION, BlockingChecker.ACTION_CHANGE, BlockingChecker.TYPE_SUBSCRIPTION, eventsStreamForBaseSubscription.getEntitlementId().toString()));
                }

                final DateTime requestedDate = dateHelper.fromLocalDateAndReferenceTime(updatedPluginContext.getEffectiveDate(), eventsStreamForBaseSubscription.getSubscriptionBase().getStartDate(), eventsStreamForBaseSubscription.getInternalTenantContext());

                try {
                    final InternalCallContext context = internalCallContextFactory.createInternalCallContext(callContext);
                    final EntitlementSpecifier specifier = getFirstEntitlementSpecifier(updatedPluginContext.getEntitlementSpecifiers());
                    final SubscriptionBase subscription = subscriptionBaseInternalApi.createSubscription(bundleId, specifier.getPlanPhaseSpecifier(), specifier.getOverrides(), requestedDate, context);

                    return new DefaultEntitlement(subscription.getId(), eventsStreamBuilder, entitlementApi, pluginExecution,
                                                  blockingStateDao, subscriptionBaseInternalApi, checker, notificationQueueService,
                                                  entitlementUtils, dateHelper, clock, securityApi, internalCallContextFactory, callContext);
                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e);
                }
            }
        };
        return pluginExecution.executeWithPlugin(addEntitlementWithPlugin, pluginContext);
    }

    @Override
    public List<EntitlementAOStatusDryRun> getDryRunStatusForChange(final UUID bundleId, final String targetProductName, final LocalDate effectiveDate, final TenantContext context) throws EntitlementApiException {
        final InternalTenantContext internalContext = internalCallContextFactory.createInternalTenantContext(context);
        try {
            final SubscriptionBaseBundle bundle = subscriptionBaseInternalApi.getBundleFromId(bundleId, internalContext);
            final SubscriptionBase baseSubscription = subscriptionBaseInternalApi.getBaseSubscription(bundleId, internalContext);

            final InternalTenantContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalTenantContext(bundle.getAccountId(), context);
            final DateTime requestedDate = dateHelper.fromLocalDateAndReferenceTime(effectiveDate, baseSubscription.getStartDate(), contextWithValidAccountRecordId);
            return subscriptionBaseInternalApi.getDryRunChangePlanStatus(baseSubscription.getId(), targetProductName, requestedDate, contextWithValidAccountRecordId);
        } catch (final SubscriptionBaseApiException e) {
            throw new EntitlementApiException(e);
        }
    }

    @Override
    public Entitlement getEntitlementForId(final UUID entitlementId, final TenantContext tenantContext) throws EntitlementApiException {
        final InternalTenantContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalTenantContext(entitlementId, ObjectType.SUBSCRIPTION, tenantContext);
        return super.getEntitlementForId(entitlementId, contextWithValidAccountRecordId);
    }

    @Override
    public List<Entitlement> getAllEntitlementsForBundle(final UUID bundleId, final TenantContext tenantContext) throws EntitlementApiException {
        final InternalTenantContext internalContext = internalCallContextFactory.createInternalTenantContext(tenantContext);
        final UUID accountId;
        try {
            accountId = subscriptionBaseInternalApi.getBundleFromId(bundleId, internalContext).getAccountId();
        } catch (final SubscriptionBaseApiException e) {
            throw new EntitlementApiException(e);
        }

        return ImmutableList.<Entitlement>copyOf(Iterables.<Entitlement>filter(getAllEntitlementsForAccountId(accountId, tenantContext),
                                                                               new Predicate<Entitlement>() {
                                                                                   @Override
                                                                                   public boolean apply(final Entitlement input) {
                                                                                       return bundleId.equals(input.getBundleId());
                                                                                   }
                                                                               }));
    }

    @Override
    public List<Entitlement> getAllEntitlementsForAccountIdAndExternalKey(final UUID accountId, final String externalKey, final TenantContext tenantContext) throws EntitlementApiException {
        // getAllEntitlementsForAccountId should be fast (uses account_record_id)
        return ImmutableList.<Entitlement>copyOf(Iterables.<Entitlement>filter(getAllEntitlementsForAccountId(accountId, tenantContext),
                                                                               new Predicate<Entitlement>() {
                                                                                   @Override
                                                                                   public boolean apply(final Entitlement input) {
                                                                                       return externalKey.equals(input.getExternalKey());
                                                                                   }
                                                                               }));
    }

    @Override
    public List<Entitlement> getAllEntitlementsForAccountId(final UUID accountId, final TenantContext tenantContext) throws EntitlementApiException {
        final EntitlementApi entitlementApi = this;
        final InternalTenantContext context = internalCallContextFactory.createInternalTenantContext(accountId, tenantContext);

        final AccountEventsStreams accountEventsStreams = eventsStreamBuilder.buildForAccount(context);
        final List<EventsStream> eventsStreams = ImmutableList.<EventsStream>copyOf(Iterables.<EventsStream>concat(accountEventsStreams.getEventsStreams().values()));
        return Lists.<EventsStream, Entitlement>transform(eventsStreams,
                                                          new Function<EventsStream, Entitlement>() {
                                                              @Override
                                                              public Entitlement apply(final EventsStream eventsStream) {
                                                                  return new DefaultEntitlement(eventsStream, eventsStreamBuilder, entitlementApi, pluginExecution,
                                                                                                blockingStateDao, subscriptionBaseInternalApi, checker, notificationQueueService,
                                                                                                entitlementUtils, dateHelper, clock, securityApi, internalCallContextFactory);
                                                              }
                                                          });
    }

    @Override
    public void pause(final UUID bundleId, final LocalDate localEffectiveDate, final Iterable<PluginProperty> properties, final CallContext context) throws EntitlementApiException {

        final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(bundleId, ObjectType.BUNDLE, context);
        super.pause(bundleId, localEffectiveDate, properties, contextWithValidAccountRecordId);
    }

    @Override
    public void resume(final UUID bundleId, final LocalDate localEffectiveDate, final Iterable<PluginProperty> properties, final CallContext context) throws EntitlementApiException {

        final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(bundleId, ObjectType.BUNDLE, context);
        super.resume(bundleId, localEffectiveDate, properties, contextWithValidAccountRecordId);

    }

    @Override
    public void setBlockingState(final UUID bundleId, final String stateName, final String serviceName, final LocalDate effectiveDate, final boolean blockBilling, final boolean blockEntitlement, final boolean blockChange, final Iterable<PluginProperty> properties, final CallContext context)
            throws EntitlementApiException {
        final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(bundleId, ObjectType.BUNDLE, context);
        super.setBlockingState(bundleId, stateName, serviceName, effectiveDate, blockBilling, blockEntitlement, blockChange, properties, contextWithValidAccountRecordId);
    }

    @Override
    public Iterable<BlockingState> getBlockingStatesForServiceAndType(final UUID blockableId, final BlockingStateType blockingStateType, final String serviceName, final TenantContext tenantContext) {
        // Not implemented see #431
        return null;
    }

    @Override
    public UUID transferEntitlements(final UUID sourceAccountId, final UUID destAccountId, final String externalKey, final LocalDate effectiveDate, final Iterable<PluginProperty> properties, final CallContext context) throws EntitlementApiException {
        return transferEntitlementsOverrideBillingPolicy(sourceAccountId, destAccountId, externalKey, effectiveDate, BillingActionPolicy.IMMEDIATE, properties, context);
    }

    @Override
    public UUID transferEntitlementsOverrideBillingPolicy(final UUID sourceAccountId, final UUID destAccountId, final String externalKey, final LocalDate effectiveDate, final BillingActionPolicy billingPolicy, final Iterable<PluginProperty> properties, final CallContext context) throws EntitlementApiException {

        final EntitlementContext pluginContext = new DefaultEntitlementContext(OperationType.TRANSFER_BUNDLE,
                                                                               sourceAccountId,
                                                                               destAccountId,
                                                                               null,
                                                                               externalKey,
                                                                               new ArrayList<EntitlementSpecifier>(),
                                                                               effectiveDate,
                                                                               properties,
                                                                               context);

        final WithEntitlementPlugin<UUID> transferWithPlugin = new WithEntitlementPlugin<UUID>() {
            @Override
            public UUID doCall(final EntitlementApi entitlementApi, final EntitlementContext updatedPluginContext) throws EntitlementApiException {
                final boolean cancelImm;
                switch (billingPolicy) {
                    case IMMEDIATE:
                        cancelImm = true;
                        break;
                    case END_OF_TERM:
                        cancelImm = false;
                        break;
                    default:
                        throw new RuntimeException("Unexpected billing policy " + billingPolicy);
                }

                final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(sourceAccountId, context);
                try {

                    final UUID activeSubscriptionIdForKey = entitlementUtils.getFirstActiveSubscriptionIdForKeyOrNull(externalKey, contextWithValidAccountRecordId);
                    final SubscriptionBase baseSubscription = activeSubscriptionIdForKey != null ?
                                                              subscriptionBaseInternalApi.getSubscriptionFromId(activeSubscriptionIdForKey, contextWithValidAccountRecordId) : null;
                    final SubscriptionBaseBundle baseBundle = baseSubscription != null ?
                                                              subscriptionBaseInternalApi.getBundleFromId(baseSubscription.getBundleId(), contextWithValidAccountRecordId) : null;

                    if (baseBundle == null || !baseBundle.getAccountId().equals(sourceAccountId)) {
                        throw new EntitlementApiException(new SubscriptionBaseApiException(ErrorCode.SUB_GET_INVALID_BUNDLE_KEY, externalKey));
                    }

                    final DateTime requestedDate = dateHelper.fromLocalDateAndReferenceTime(updatedPluginContext.getEffectiveDate(), baseSubscription.getStartDate(), contextWithValidAccountRecordId);
                    final SubscriptionBaseBundle newBundle = subscriptionBaseTransferApi.transferBundle(sourceAccountId, destAccountId, externalKey, requestedDate, true, cancelImm, context);

                    // Block all associated subscriptions - TODO Do we want to block the bundle as well (this will add an extra STOP_ENTITLEMENT event in the bundle timeline stream)?
                    // Note that there is no un-transfer at the moment, so we effectively add a blocking state on disk for all subscriptions
                    final Map<BlockingState, UUID> blockingStates = new HashMap<BlockingState, UUID>();
                    for (final SubscriptionBase subscriptionBase : subscriptionBaseInternalApi.getSubscriptionsForBundle(baseBundle.getId(), null, contextWithValidAccountRecordId)) {
                        final BlockingState blockingState = new DefaultBlockingState(subscriptionBase.getId(), BlockingStateType.SUBSCRIPTION, DefaultEntitlementApi.ENT_STATE_CANCELLED, EntitlementService.ENTITLEMENT_SERVICE_NAME, true, true, false, requestedDate);
                        blockingStates.put(blockingState, subscriptionBase.getBundleId());
                    }
                    entitlementUtils.setBlockingStateAndPostBlockingTransitionEvent(blockingStates, contextWithValidAccountRecordId);

                    return newBundle.getId();
                } catch (final SubscriptionBaseTransferApiException e) {
                    throw new EntitlementApiException(e);
                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e);
                }
            }
        };
        return pluginExecution.executeWithPlugin(transferWithPlugin, pluginContext);
    }
}
