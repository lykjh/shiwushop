package com.shiwushop.member.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.shiwushop.member.model.MemberGrowthEntry;

public record MemberGrowthEntryVO(
    /** 成长账本流水 ID。 */
    Long id,
    /** 账本事件类型。 */
    String eventKind,
    /** 关联订单明细 ID。 */
    Long orderItemId,
    /** 关联售后单 ID。 */
    Long afterSaleCaseId,
    /** 本次调整的金额，正数增加、负数扣减，单位元。 */
    BigDecimal deltaAmount,
    /** 本次变动后的余额，单位元。 */
    BigDecimal balanceAfter,
    /** 变动前会员等级代码。 */
    String oldLevelCode,
    /** 变动后会员等级代码。 */
    String newLevelCode,
    /** 记录创建时间。 */
    OffsetDateTime createdAt,
    /** 本次业务操作的原因。 */
    String reason
) {
    public static MemberGrowthEntryVO from(MemberGrowthEntry entry) {
        return new MemberGrowthEntryVO(entry.getId(), entry.getEventKind(), entry.getOrderItemId(),
                entry.getAfterSaleCaseId(),
                entry.getDeltaAmount(), entry.getBalanceAfter(), entry.getOldLevelCode(), entry.getNewLevelCode(),
                entry.getCreatedAt().atOffset(ZoneOffset.ofHours(8)), entry.getReason());
    }
}
