package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.mapper.MarketingActivityMapper;
import com.shiwushop.marketing.mapper.MarketingBudgetHoldMapper;
import com.shiwushop.marketing.model.MarketingBudgetHold;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import com.shiwushop.marketing.PromotionAmounts;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

@Component
@RequiredArgsConstructor
public class CouponBudgetCommitments {
    private final MarketingActivityMapper activities;
    private final MarketingBudgetHoldMapper holds;
    private final Clock businessClock;

    /** 发券时冻结可兑现的承担额度，防止券已发出后才发现预算不足。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Long activity, Long coupon, BigDecimal face, LocalDateTime expiresAt) {
        var budgets = activities.lockBudgets(activity);
        var amounts = new HashMap<Long, BigDecimal>();
        BigDecimal platform = BigDecimal.ZERO;
        for (var budget : budgets) {
            if (!budget.party().equals("MERCHANT"))
                continue;
            var platformPart = PromotionAmounts.platformShare(face, budget.platformShareBp());
            // 平台按可选店铺的最大份额预占；一张券不会同时在所有店铺兑现。
            platform = platform.max(platformPart);
            amounts.put(budget.id(), face.subtract(platformPart));
        }
        for (var budget : budgets)
            if (budget.party().equals("PLATFORM"))
                amounts.put(budget.id(), platform);
        for (var budget : budgets) {
            var amount = amounts.get(budget.id());
            if (amount.compareTo(budget.budgetAmount().subtract(budget.heldAmount()).subtract(budget.spentAmount())) >
                    0)
                throw new BusinessException(409, "券承担方预算不足", Map.of("reason", "BUDGET_NOT_ENOUGH"), null);
        }
        for (var budget : budgets) {
            var amount = amounts.get(budget.id());
            if (amount.signum() == 0)
                continue;
            var hold = new MarketingBudgetHold();
            hold.setBudgetId(budget.id());
            hold.setOperationKey("COUPON:" + coupon + ":ISSUE");
            hold.setSourceKind("COUPON");
            hold.setSourceId(coupon);
            hold.setAuthorizedAmount(amount);
            hold.setHeldAmount(amount);
            hold.setExpiresAt(expiresAt);
            holds.insert(hold);
            holds.held(budget.id(), amount);
            holds.entry(budget.id(), hold.getId(), hold.getOperationKey(), "RESERVE", amount,
                    LocalDateTime.now(businessClock));
        }
    }

    /** 仅释放仍挂在券上的承诺，已转入订单的部分由订单结算或退款处理。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseExpired(Long activity, Long coupon) {
        activities.lockBudgets(activity);
        for (var hold : holds.couponHolds(coupon)) {
            if (hold.getHeldAmount().signum() == 0)
                continue;
            holds.held(hold.getBudgetId(), hold.getHeldAmount().negate());
            holds.release(hold.getId());
            holds.entry(hold.getBudgetId(), hold.getId(), "COUPON:" + coupon + ":EXPIRE:" + hold.getId(), "RELEASE",
                    hold.getHeldAmount().negate(), LocalDateTime.now(businessClock));
        }
    }
}
