package com.shiwushop.marketing;

import com.shiwushop.marketing.service.MarketingCouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MarketingCouponExpiryScheduler {
    private final MarketingCouponService coupons;

    @Scheduled(fixedDelay = 5000)
    public void expire() {
        for (long id : coupons.expiredCoupons())
            coupons.expire(id);
    }
}
