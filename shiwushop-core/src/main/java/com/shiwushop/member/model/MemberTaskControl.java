package com.shiwushop.member.model;

import java.time.LocalDateTime;

public record MemberTaskControl(
    /** 当前记录 ID。 */
    Long id,
    /** 权益任务状态，区分等待支付/收货/售后、待入账、已入账、排除及人工处理。 */
    String status,
    /** 满足处理条件的最早时间。 */
    LocalDateTime eligibleAt,
    /** 任务允许再次尝试的时间。 */
    LocalDateTime nextAttemptAt,
    /** 当前工作租约的截止时间。 */
    LocalDateTime claimUntil
) {
}
