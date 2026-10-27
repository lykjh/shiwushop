package com.shiwushop.commerce;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
class BackgroundJobScheduling {
    @Bean
    ThreadPoolTaskScheduler paymentExpiryScheduler() {
        return scheduler("payment-expiry-");
    }

    @Bean
    ThreadPoolTaskScheduler refundReconciliationScheduler() {
        return scheduler("refund-reconciliation-");
    }

    @Bean
    ThreadPoolTaskScheduler shipmentExpiryScheduler() {
        return scheduler("shipment-expiry-");
    }

    @Bean
    ThreadPoolTaskScheduler shipmentOutboxScheduler() {
        return scheduler("shipment-outbox-");
    }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // Each job gets one thread so a slow external dependency cannot hold another scan.
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }
}
