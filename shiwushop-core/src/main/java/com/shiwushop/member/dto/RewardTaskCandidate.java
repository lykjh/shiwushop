package com.shiwushop.member.dto;

public record RewardTaskCandidate(
    /** 会员权益处理任务 ID。 */
    Long taskId,
    /** 关联订单 ID。 */
    Long orderId
) {
}
