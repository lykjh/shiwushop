package com.shiwushop.marketing;

import com.shiwushop.marketing.service.MarketingGroupExpiryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "shop.marketing.enabled", havingValue = "true")
public class MarketingGroupExpiryScheduler {
    private final MarketingGroupExpiryService groups;

    @Scheduled(fixedDelay = 5000)
    public void expire() {
        for (long id : groups.due())
            groups.expire(id);
    }
}
