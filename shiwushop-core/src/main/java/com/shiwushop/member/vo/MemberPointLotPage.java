package com.shiwushop.member.vo;

import java.util.List;

public record MemberPointLotPage(
    /** 当前对象的明细列表。 */
    List<MemberPointLotVO> items,
    /** 是否还有下一页。 */
    boolean hasMore,
    /** 下一页查询游标。 */
    Long nextCursor
) {
}
