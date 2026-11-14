package com.shiwushop.member.vo;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.time.OffsetDateTime;

public record AdminRewardTaskVO(
    /** 会员权益任务详情。 */
    @JsonUnwrapped MemberRewardTaskVO task,
    /** 所属用户 ID。 */
    Long userId,
    /** 权益或超时任务采用的规则版本。 */
    int ruleVersion,
    /** 任务允许再次尝试的时间。 */
    OffsetDateTime nextAttemptAt,
    /** 任务最近一次处理失败的错误代码。 */
    String lastErrorCode
) {
}
