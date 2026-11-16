package com.shiwushop.marketing;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.service.MonthlyCouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.*;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class MonthlyCouponScheduler {
    private final MonthlyCouponService service;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${shop.marketing.monthly-scan-ms:60000}")
    public void advance() {
        if (!service.enabled())
            return;
        // 历史月份共用单轮额度，避免一次补扫占满调度线程。
        int remaining = 50;
        for (var month = service.firstMonth();
             month != null && !month.isAfter(YearMonth.now(clock)) && remaining > 0; month = month.plusMonths(1)) {
            var users = service.missing(month, remaining);
            for (long user : users)
                service.establish(user, month);
            remaining -= users.size();
        }
        for (long id : service.due()) {
            var token = service.acquire(id);
            if (token == null)
                continue;
            try {
                service.deliver(id, token);
            } catch (BusinessException e) {
                // 只有可人工处理的额度／发行量冲突进入待办，未知故障继续显式暴露。
                if (!(e.getData() instanceof Map<?, ?> data) || !(data.get("reason") instanceof String reason)
                        || !Set.of("BUDGET_NOT_ENOUGH", "COUPON_NOT_USABLE").contains(reason))
                    throw e;
                service.attention(id, token, reason);
                log.warn("月券发放等待处理 grantId={} reason={}", id, reason);
            }
        }
    }
}
