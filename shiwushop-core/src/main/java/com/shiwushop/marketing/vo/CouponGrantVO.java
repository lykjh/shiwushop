package com.shiwushop.marketing.vo;

import java.time.OffsetDateTime;
import java.util.List;

public record CouponGrantVO(
    /** 发券义务 ID。 */
    Long id,
    /** 发券来源：MEMBERSHIP 月券、CLAIM 领取、CODE 兑换、COMPENSATION 补偿。 */
    String sourceKind,
    /** 发券义务状态：PENDING 待发、GRANTED 已发、EXPIRED 过期、ATTENTION 待处理。 */
    String state,
    /** 本次发券义务应发的总张数。 */
    int entitledCount,
    /** 有效截止时间。 */
    OffsetDateTime expiresAt,
    /** 该发券义务下的用户券实例列表。 */
    List<CouponVO> coupons
) {
    public record Page(
        /** 当前对象的明细列表。 */
        List<CouponGrantVO> items,
        /** 是否还有下一页。 */
        boolean hasMore,
        /** 下一页查询游标。 */
        Long nextCursor
    ) {
    }
}
