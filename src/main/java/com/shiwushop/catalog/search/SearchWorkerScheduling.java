package com.shiwushop.catalog.search;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
class SearchWorkerScheduling {
    /** 为搜索索引任务提供独立单线程调度器，避免搜索等待占用支付或物流任务线程。 */
    @Bean
    ThreadPoolTaskScheduler searchTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // Isolate indexing waits from payment, shipment and other scheduled work.
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("search-worker-");
        return scheduler;
    }
}
