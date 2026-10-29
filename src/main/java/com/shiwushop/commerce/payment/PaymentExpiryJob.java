package com.shiwushop.commerce.payment;

import com.shiwushop.commerce.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentExpiryJob {
    private final PaymentService paymentService;

    @Scheduled(initialDelayString = "${shop.payment.expiry-scan-delay-ms}",
            fixedDelayString = "${shop.payment.expiry-scan-delay-ms}", scheduler = "paymentExpiryScheduler")
    public void expireDueOrders() {
        paymentService.expireDueOrders();
    }
}
