package com.shiwushop.aftersale.job;

import com.shiwushop.aftersale.service.AfterSaleTimeoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AfterSaleTimeoutJob {
    private final AfterSaleTimeoutService service;

    /** 定期生成或过期售后待办，不推进售后业务状态。 */
    @Scheduled(initialDelayString = "${shop.aftersale.timeout-scan-delay-ms:60000}",
            fixedDelayString = "${shop.aftersale.timeout-scan-delay-ms:60000}", scheduler = "refundReconciliationScheduler")
    public void scan() {
        service.scan();
    }
}
