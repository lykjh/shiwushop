package com.shiwushop.marketing.vo;

import com.shiwushop.marketing.model.CouponCode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public record CouponCodeVO(
    /** 兑换码记录 ID。 */
    Long id,
    /** 关联营销活动 ID。 */
    Long activityId,
    /** 兑换码类型：PUBLIC 公共码、SINGLE 单次码。 */
    String codeKind,
    /** 兑换码允许的最大兑换次数。 */
    int maxRedemptions,
    /** 兑换码已成功兑换次数。 */
    int redeemedCount,
    /** 有效截止时间。 */
    OffsetDateTime expiresAt,
    /** 兑换码状态：ACTIVE 启用、DISABLED 停用。 */
    String status
) {
    public static CouponCodeVO from(CouponCode code) {
        return new CouponCodeVO(code.getId(), code.getActivityId(), code.getCodeKind(), code.getMaxRedemptions(),
                code.getRedeemedCount(),
                code.getExpiresAt().atOffset(ZoneOffset.ofHours(8)), code.getStatus());
    }

    public record Page(
        /** 当前对象的明细列表。 */
        List<CouponCodeVO> items,
        /** 是否还有下一页。 */
        boolean hasMore,
        /** 下一页查询游标。 */
        Long nextCursor
    ) {
    }
}
