package com.shiwushop.marketing;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.mapper.MarketingActivityMapper;
import com.shiwushop.product.service.SkuPriceConstraint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MarketingSkuPriceConstraint implements SkuPriceConstraint {
    private final MarketingActivityMapper mapper;
    private final Clock businessClock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void check(Long skuId, BigDecimal newPrice) {
        var conflicts = mapper.priceConflicts(skuId, newPrice, LocalDateTime.now(businessClock));
        if (!conflicts.isEmpty())
            throw new BusinessException(409, "普通价不能低于已批准会员价，请先修订活动",
                    Map.of("reason", "INVALID_STATE", "activityIds", conflicts), null);
    }
}
