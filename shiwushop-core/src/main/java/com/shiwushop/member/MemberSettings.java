package com.shiwushop.member;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 启用边界必须固定配置，禁止每次重启重新以 now 计算历史订单资格。 */
@Component
public class MemberSettings {
    private final boolean enabled;
    private final LocalDateTime rewardStartAt;

    public MemberSettings(@Value("${shop.member.enabled:false}") boolean enabled,
                          @Value("${shop.member.reward-start-at:}") String rewardStartAt) {
        this.enabled = enabled;
        if (enabled && rewardStartAt.isBlank())
            throw new IllegalArgumentException("启用会员必须配置 shop.member.reward-start-at（北京时间）");
        this.rewardStartAt = rewardStartAt.isBlank() ? null : LocalDateTime.parse(rewardStartAt);
    }

    public boolean enabled() {
        return enabled;
    }

    public LocalDateTime rewardStartAt() {
        return rewardStartAt;
    }
}
