package com.shiwushop.member.vo;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record AdminPointEntryVO(
    /** 会员账本流水详情。 */
    @JsonUnwrapped MemberPointEntryVO entry,
    /** 本次操作人用户 ID。 */
    Long actorUserId,
    /** 操作凭据引用。 */
    String evidenceRef
) {
}
