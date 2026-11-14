package com.shiwushop.member.vo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.shiwushop.member.model.MemberPointEntry;

public record MemberPointEntryVO(
    /** 积分账本流水 ID。 */
    Long id,
    /** 账本事件类型。 */
    String eventKind,
    /** 关联订单 ID。 */
    Long orderId,
    /** 关联售后单 ID。 */
    Long afterSaleCaseId,
    /** 本次可用余额变化量，以积分最小单位计；100 单位为 1 积分。 */
    long availableDelta,
    /** 本次占用余额变化量，以积分最小单位计；100 单位为 1 积分。 */
    long heldDelta,
    /** 本次积分欠额增减的最小单位数，以积分最小单位计；100 单位为 1 积分。 */
    long debtDelta,
    /** 本次记账后的可用积分最小单位数；100 单位为 1 积分。 */
    long availableAfter,
    /** 本次记账后的占用余额，以积分最小单位计；100 单位为 1 积分。 */
    long heldAfter,
    /** 本次记账后的积分欠额最小单位数；100 单位为 1 积分。 */
    long debtAfter,
    /** 记录创建时间。 */
    OffsetDateTime createdAt,
    /** 本次业务操作的原因。 */
    String reason
) {
    public static MemberPointEntryVO from(MemberPointEntry entry) {
        return new MemberPointEntryVO(entry.getId(), entry.getEventKind(), entry.getOrderId(),
                entry.getAfterSaleCaseId(),
                entry.getAvailableDelta(), entry.getHeldDelta(), entry.getDebtDelta(), entry.getAvailableAfter(),
                entry.getHeldAfter(), entry.getDebtAfter(), entry.getCreatedAt().atOffset(ZoneOffset.ofHours(8)),
                entry.getReason());
    }
}
