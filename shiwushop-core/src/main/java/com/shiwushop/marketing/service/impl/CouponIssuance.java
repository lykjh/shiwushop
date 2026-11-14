package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.mapper.*;
import com.shiwushop.marketing.model.*;
import com.shiwushop.marketing.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 在调用方发放事务内写券和预算承诺，不能只提交券或只提交扣额。 */
@Component
@RequiredArgsConstructor
class CouponIssuance {
    private final MarketingCouponMapper mapper;
    private final MarketingActivityMapper activities;
    private final MarketingBudgetMapper budgets;
    private final CouponBudgetCommitments commitments;
    private final Clock clock;

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    private static void conflict(String reason, String message) {
        throw new BusinessException(409, message, Map.of("reason", reason), null);
    }

    CouponVO couponView(UserCoupon coupon) {
        var shops = budgets.list(coupon.getActivityId(), null).stream().map(MarketingBudget::shopId)
                .filter(Objects::nonNull).toList();
        return CouponVO.from(coupon, shops, activities.skuIds(coupon.getActivityId()), now());
    }

    CouponGrantVO grantView(CouponGrant grant, boolean lock) {
        var coupons = lock ? mapper.lockGrantCoupons(grant.getId()) : mapper.grantCoupons(grant.getId());
        return new CouponGrantVO(grant.getId(), grant.getSourceKind(), grant.getStatus(), grant.getEntitledCount(),
                grant.getExpiresAt().atOffset(ZoneOffset.ofHours(8)), coupons.stream().map(this::couponView).toList());
    }

    /** 月券数量由发放义务冻结，其他来源同时受账号累计领取上限约束。 */
    void issue(CouponGrant grant, MarketingActivity activity, ActivityCouponRule rule) {
        if (!"MEMBERSHIP".equals(grant.getSourceKind())) {
            mapper.initializeQuota(activity.getId(), activity.getActivityCode(), grant.getUserId());
            if (mapper.consumeQuota(activity.getId(), activity.getActivityCode(), grant.getUserId(),
                    grant.getEntitledCount(), rule.perUserLimit()) != 1)
                conflict("PURCHASE_LIMIT_REACHED", "已达到该活动累计领券上限");
        }
        if (mapper.issue(activity.getId(), grant.getEntitledCount()) != 1)
            conflict("COUPON_NOT_USABLE", "券发放总量已达上限");
        // 同一发放义务内保留稳定张序号，便于逐张追溯发券与预算占用。
        for (int ordinal = 1; ordinal <= grant.getEntitledCount(); ordinal++) {
            var coupon = new UserCoupon();
            coupon.setGrantId(grant.getId());
            coupon.setOrdinalNo(ordinal);
            coupon.setUserId(grant.getUserId());
            coupon.setActivityId(grant.getActivityId());
            coupon.setFaceAmount(grant.getFaceAmount());
            coupon.setThresholdAmount(grant.getThresholdAmount());
            coupon.setValidFrom(grant.getBenefitMonth() == null ? now() : grant.getBenefitMonth().atStartOfDay());
            coupon.setExpiresAt(grant.getExpiresAt());
            mapper.insertCoupon(coupon);
            commitments.reserve(activity.getId(), coupon.getId(), coupon.getFaceAmount(), coupon.getExpiresAt());
        }
        mapper.granted(grant.getId());
        grant.setStatus("GRANTED");
    }
}
