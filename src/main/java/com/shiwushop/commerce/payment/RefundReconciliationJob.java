package com.shiwushop.commerce.payment;

import com.shiwushop.commerce.service.AfterSaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefundReconciliationJob {
    private final AfterSaleService afterSaleService;

    /** 定期委托售后服务核对渠道结果未知的退款。 */
    @Scheduled(initialDelayString = "${shop.payment.refund-scan-delay-ms}",
            fixedDelayString = "${shop.payment.refund-scan-delay-ms}", scheduler = "refundReconciliationScheduler")
    public void reconcile() {
        afterSaleService.reconcileUnknown();
    }
}
