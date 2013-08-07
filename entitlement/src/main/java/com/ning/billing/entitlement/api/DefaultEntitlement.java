/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
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

package com.ning.billing.entitlement.api;

import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;

import com.ning.billing.account.api.Account;
import com.ning.billing.account.api.AccountApiException;
import com.ning.billing.catalog.api.ActionPolicy;
import com.ning.billing.catalog.api.BillingPeriod;
import com.ning.billing.catalog.api.Plan;
import com.ning.billing.catalog.api.PlanPhase;
import com.ning.billing.catalog.api.PriceList;
import com.ning.billing.catalog.api.Product;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.clock.Clock;
import com.ning.billing.entitlement.block.BlockingChecker;
import com.ning.billing.subscription.api.SubscriptionBase;
import com.ning.billing.subscription.api.user.SubscriptionUserApiException;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.callcontext.InternalCallContext;
import com.ning.billing.util.callcontext.InternalCallContextFactory;
import com.ning.billing.util.svcapi.account.AccountInternalApi;
import com.ning.billing.util.timezone.DateAndTimeZoneContext;

public class DefaultEntitlement implements Entitlement {

    private final AccountInternalApi accountApi;
    private final SubscriptionBase subscription;
    private final InternalCallContextFactory internalCallContextFactory;
    private final Clock clock;
    private final BlockingChecker checker;
    private final UUID accountId;

    public DefaultEntitlement(final AccountInternalApi accountApi, final SubscriptionBase subscription, final UUID accountId, final InternalCallContextFactory internalCallContextFactory, final Clock clock, final BlockingChecker checker) {
        this.accountApi = accountApi;
        this.subscription = subscription;
        this.accountId = accountId;
        this.internalCallContextFactory = internalCallContextFactory;
        this.clock = clock;
        this.checker = checker;
    }


    @Override
    public boolean cancelEntitlementWithDate(final LocalDate localDate, final CallContext callContext) throws EntitlementApiException {

        final InternalCallContext context = internalCallContextFactory.createInternalCallContext(accountId, callContext);
        final DateTime requestedDate = fromLocalDateAndReferenceTime(localDate, subscription.getStartDate(), clock, context);
        try {
            return subscription.cancel(requestedDate, callContext);
        } catch (SubscriptionUserApiException e) {
            throw new EntitlementApiException(e);
        }
    }


    @Override
    public boolean cancelEntitlementWithPolicy(final EntitlementActionPolicy entitlementActionPolicy, final CallContext callContext) throws EntitlementApiException {
        return false;
    }

    @Override
    public boolean cancelEntitlementWithDateOverrideBillingPolicy(final LocalDate effectiveDate, final ActionPolicy billingPolicy, final CallContext context) throws EntitlementApiException {
        return false;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public boolean cancelEntitlementWithPolicyOverrideBillingPolicy(final EntitlementActionPolicy policy, final ActionPolicy billingPolicy, final CallContext context) throws EntitlementApiException {
        return false;  //To change body of implemented methods use File | Settings | File Templates.
    }


    @Override
    public boolean changePlan(final String productName, final BillingPeriod billingPeriod, final String priceList, final LocalDate localDate, final CallContext callContext) throws EntitlementApiException {

        final InternalCallContext context = internalCallContextFactory.createInternalCallContext(accountId, callContext);
        final DateTime requestedDate = fromLocalDateAndReferenceTime(localDate, subscription.getStartDate(), clock, context);
        try {
            checker.checkBlockedChange(subscription, context);
            return subscription.changePlan(productName, billingPeriod, priceList, requestedDate, callContext);
        } catch (BlockingApiException e) {
            throw new EntitlementApiException(e, e.getCode(), e.getMessage());
        } catch (SubscriptionUserApiException e) {
            throw new EntitlementApiException(e);
        }
    }

    @Override
    public boolean changePlanOverrideBillingPolicy(final String productName, final BillingPeriod billingPeriod, final String priceList, final LocalDate localDate, final ActionPolicy actionPolicy, final CallContext callContext) throws EntitlementApiException {
        final InternalCallContext context = internalCallContextFactory.createInternalCallContext(accountId, callContext);
        final DateTime requestedDate = fromLocalDateAndReferenceTime(localDate, subscription.getStartDate(), clock, context);
        try {
            checker.checkBlockedChange(subscription, context);
            return subscription.changePlanWithPolicy(productName, billingPeriod, priceList, requestedDate, actionPolicy, callContext);
        } catch (BlockingApiException e) {
            throw new EntitlementApiException(e, e.getCode(), e.getMessage());
        } catch (SubscriptionUserApiException e) {
            throw new EntitlementApiException(e);
        }
    }

    @Override
    public boolean pause(final LocalDate localDate, final CallContext callContext) throws EntitlementApiException {
        return false;
    }

    @Override
    public boolean resume(final LocalDate localDate, final CallContext callContext) throws EntitlementApiException {
        return false;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public SubscriptionBase getSubscription() {
        return subscription;
    }

    @Override
    public UUID getId() {
        return subscription.getId();
    }

    @Override
    public String getExternalKey() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public EntitlementState getState() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public EntitlementSourceType getSourceType() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public LocalDate getEffectiveStartDate() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public LocalDate getEffectiveEndDate() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public LocalDate getRequestedEndDate() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Product getProduct() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Plan getPlan() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public PriceList getPriceList() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public PlanPhase getCurrentPhase() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public ProductCategory getProductCategory() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Product getLastActiveProduct() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Plan getLastActivePlan() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public PriceList getLastActivePriceList() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public String getLastActiveProductCategory() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }


    /**
     * Returns a DateTime that is equals or beforeNow and whose LocalDate using the account timeZone is the one provided
     * <p/>
     * Relies on the subscriptionStartDate for the reference time
     *
     * @param requestedDate
     * @param subscriptionStartDate
     * @param clock
     * @param callContext
     * @return
     * @throws EntitlementApiException
     */
    private DateTime fromLocalDateAndReferenceTime(final LocalDate requestedDate, final DateTime subscriptionStartDate, final Clock clock, final InternalCallContext callContext) throws EntitlementApiException {
        try {
            final Account account = accountApi.getAccountByRecordId(callContext.getAccountRecordId(), callContext);
            final DateAndTimeZoneContext timeZoneContext = new DateAndTimeZoneContext(subscriptionStartDate, account.getTimeZone(), clock);
            final DateTime computedTime = timeZoneContext.computeUTCDateTimeFromLocalDate(requestedDate);

            return computedTime.isAfter(clock.getUTCNow()) ? clock.getUTCNow() : computedTime;
        } catch (AccountApiException e) {
            throw new EntitlementApiException(e);
        }
    }


}