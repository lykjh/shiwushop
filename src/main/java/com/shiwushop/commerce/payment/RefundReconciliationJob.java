package com.shiwushop.commerce.payment;

import com.shiwushop.service.AfterSaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefundReconciliationJob {
    private final AfterSaleService afterSaleService;

    @Scheduled(initialDelayString = "${shop.payment.refund-scan-delay-ms}",
            fixedDelayString = "${shop.payment.refund-scan-delay-ms}", scheduler = "refundReconciliationScheduler")
    public void reconcile() {
        afterSaleService.reconcileUnknown();
    }
}
