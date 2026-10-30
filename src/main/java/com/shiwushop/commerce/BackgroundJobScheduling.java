package com.shiwushop.commerce;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
class BackgroundJobScheduling {
    /** 为订单到期扫描提供独立的单线程调度器。 */
    @Bean
    ThreadPoolTaskScheduler paymentExpiryScheduler() {
        return scheduler("payment-expiry-");
    }

    /** 为退款对账提供独立调度器，避免支付扫描占用其线程。 */
    @Bean
    ThreadPoolTaskScheduler refundReconciliationScheduler() {
        return scheduler("refund-reconciliation-");
    }

    /** 为物流到期确认提供独立调度器。 */
    @Bean
    ThreadPoolTaskScheduler shipmentExpiryScheduler() {
        return scheduler("shipment-expiry-");
    }

    /** 为物流发件箱投递提供独立调度器。 */
    @Bean
    ThreadPoolTaskScheduler shipmentOutboxScheduler() {
        return scheduler("shipment-outbox-");
    }

    /** 每种后台任务只运行一条调度线程，并使用可辨识的线程名前缀。 */
    private ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // Each job gets one thread so a slow external dependency cannot hold another scan.
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }
}
