package com.shiwushop.member.vo;

import java.time.OffsetDateTime;

public record TaskRetryResult(
    /** 会员权益处理任务 ID。 */
    Long taskId,
    /** 权益任务重新排队后的状态。 */
    String state,
    /** 任务允许再次尝试的时间。 */
    OffsetDateTime nextAttemptAt
) {
}
