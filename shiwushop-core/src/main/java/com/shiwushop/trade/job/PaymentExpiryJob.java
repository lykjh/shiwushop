package com.shiwushop.trade.job;

import com.shiwushop.trade.service.OrderPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentExpiryJob {
    private final OrderPaymentService paymentService;

    /** 定期委托支付服务核对到期未付款订单。 */
    @Scheduled(initialDelayString = "${shop.payment.expiry-scan-delay-ms}",
            fixedDelayString = "${shop.payment.expiry-scan-delay-ms}", scheduler = "paymentExpiryScheduler")
    public void expireDueOrders() {
        paymentService.expireDueOrders();
    }
}
