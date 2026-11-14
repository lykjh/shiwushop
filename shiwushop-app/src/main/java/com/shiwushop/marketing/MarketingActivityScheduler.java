package com.shiwushop.marketing;

import com.shiwushop.marketing.service.MarketingActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "shop.marketing.enabled", havingValue = "true")
public class MarketingActivityScheduler {
    private final MarketingActivityService activities;

    @Scheduled(fixedDelay = 5000)
    public void advance() {
        for (long id : activities.due())
            activities.advance(id);
    }
}
