package com.shiwushop.catalog.search;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
class SearchWorkerScheduling {
    @Bean
    ThreadPoolTaskScheduler searchTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // Isolate indexing waits from payment, shipment and other scheduled work.
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("search-worker-");
        return scheduler;
    }
}
