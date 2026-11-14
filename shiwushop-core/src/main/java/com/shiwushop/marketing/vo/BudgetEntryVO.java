package com.shiwushop.marketing.vo;

import com.shiwushop.marketing.model.MarketingBudgetEntry;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record BudgetEntryVO(
    /** 当前记录 ID。 */
    Long id,
    /** 营销预算 ID。 */
    Long budgetId,
    /** 账本事件类型。 */
    String eventKind,
    /** 本次占用余额变化量，单位元。 */
    BigDecimal heldDelta,
    /** 本次预算支出增减金额，单位元。 */
    BigDecimal spentDelta,
    /** 本次记账后的占用余额，单位元。 */
    BigDecimal heldAfter,
    /** 本次记账后的预算已支出金额，单位元。 */
    BigDecimal spentAfter,
    /** 关联订单 ID。 */
    Long orderId,
    /** 关联售后单 ID。 */
    Long afterSaleCaseId,
    /** 记录创建时间。 */
    OffsetDateTime createdAt,
    /** 本次业务操作的原因。 */
    String reason,
    /** 本次预算总额度的增减金额，单位元。 */
    BigDecimal budgetDeltaAmount,
    /** 本次操作后的预算总额度，单位元。 */
    BigDecimal budgetAfter,
    /** 本次操作人用户 ID。 */
    Long actorUserId,
    /** 操作凭据引用。 */
    String evidenceRef
) {
    public static BudgetEntryVO from(MarketingBudgetEntry entry) {
        return new BudgetEntryVO(entry.getId(), entry.getBudgetId(), entry.getEventKind(), entry.getHeldDelta(),
                entry.getSpentDelta(), entry.getHeldAfter(), entry.getSpentAfter(), entry.getOrderId(),
                entry.getAfterSaleCaseId(),
                entry.getCreatedAt().atOffset(ZoneOffset.ofHours(8)), entry.getReason(), entry.getBudgetDeltaAmount(),
                entry.getBudgetAfter(), entry.getActorUserId(), entry.getEvidenceReference());
    }
}
