package com.shiwushop.commerce;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundJobSchedulingTests {
    @Test
    void slowPaymentTaskDoesNotBlockRefundScheduler() throws Exception {
        BackgroundJobScheduling config = new BackgroundJobScheduling();
        ThreadPoolTaskScheduler payment = config.paymentExpiryScheduler();
        ThreadPoolTaskScheduler refund = config.refundReconciliationScheduler();
        payment.initialize();
        refund.initialize();
        CountDownLatch paymentStarted = new CountDownLatch(1);
        CountDownLatch releasePayment = new CountDownLatch(1);
        CountDownLatch refundRan = new CountDownLatch(1);
        try {
            payment.schedule(() -> {
                paymentStarted.countDown();
                try {
                    releasePayment.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, Instant.now());
            assertTrue(paymentStarted.await(2, TimeUnit.SECONDS));
            refund.schedule(refundRan::countDown, Instant.now());
            assertTrue(refundRan.await(2, TimeUnit.SECONDS));
        } finally {
            releasePayment.countDown();
            payment.shutdown();
            refund.shutdown();
        }
    }
}
