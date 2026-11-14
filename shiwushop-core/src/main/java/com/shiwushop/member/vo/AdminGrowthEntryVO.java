package com.shiwushop.member.vo;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record AdminGrowthEntryVO(
    /** 会员账本流水详情。 */
    @JsonUnwrapped MemberGrowthEntryVO entry,
    /** 被冲回的原成长入账流水 ID。 */
    Long sourceEntryId,
    /** 本次操作人用户 ID。 */
    Long actorUserId,
    /** 操作凭据引用。 */
    String evidenceRef
) {
}
