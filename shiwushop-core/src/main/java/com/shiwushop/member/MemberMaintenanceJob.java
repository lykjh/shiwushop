package com.shiwushop.member;

import com.shiwushop.member.service.MemberMaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 初始化和积分过期以账户事务串行，进度分别由账户唯一键及批次余额持久化。 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "shop.member.enabled", havingValue = "true")
public class MemberMaintenanceJob {
    private final MemberMaintenanceService maintenance;

    @Scheduled(fixedDelayString = "${shop.member.scan-delay-ms:60000}")
    public void scan() {
        long start = System.nanoTime();
        for (Long id : maintenance.usersWithExpiredPoints()) {
            if (System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(20))
                return;
            try {
                maintenance.expirePoints(id);
            } catch (RuntimeException error) {
                log.error("积分过期失败 userId={}", id, error);
            }
        }
        for (Long id : maintenance.uninitializedUsers()) {
            if (System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(20))
                return;
            try {
                maintenance.initialize(id);
            } catch (RuntimeException error) {
                log.error("会员初始化失败 userId={}", id, error);
            }
        }
    }
}
