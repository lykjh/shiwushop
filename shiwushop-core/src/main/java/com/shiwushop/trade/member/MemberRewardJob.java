package com.shiwushop.trade.member;

import com.shiwushop.member.service.MemberTaskService;
import com.shiwushop.trade.service.OrderRewardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "shop.member.enabled", havingValue = "true")
public class MemberRewardJob {
    private final MemberTaskService tasks;
    private final OrderRewardService orders;

    @Scheduled(fixedDelayString = "${shop.member.scan-delay-ms:60000}")
    public void scan() {
        long started = System.nanoTime();
        for (var task : tasks.pending()) {
            if (System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(20))
                break;
            String token = UUID.randomUUID().toString();
            if (!tasks.claim(task.taskId(), token))
                continue;
            try {
                orders.reconcile(task.orderId(), task.taskId(), token);
            } catch (RuntimeException error) {
                // 单项失败保留待办，不阻断本轮其他订单；不记录私密请求参数。
                log.error("会员权益任务失败 taskId={} orderId={}", task.taskId(), task.orderId(), error);
                tasks.release(task.taskId(), token, error.getClass().getSimpleName());
            }
        }
    }
}
