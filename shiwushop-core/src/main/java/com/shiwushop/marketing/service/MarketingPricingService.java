package com.shiwushop.marketing.service;

import com.shiwushop.marketing.dto.ShopChoice;
import com.shiwushop.marketing.vo.QuoteVO;

import java.math.BigDecimal;
import java.util.List;

public interface MarketingPricingService {
    record Line(Long skuId, Long shopId, Long ownerUserId, int quantity, BigDecimal ordinaryPrice) {
    }

    record Allocation(Long skuId, Long shopId, Long activityId, String kind, Long userCouponId, Long tierId,
                      BigDecimal amount, BigDecimal platformAmount, int platformShareBp, String ruleSnapshot) {
    }

    record Price(QuoteVO quote, List<Allocation> allocations) {
    }

    Price price(Long user, String level, List<Line> lines, List<ShopChoice> choices, String addressHash, boolean lock);
}
