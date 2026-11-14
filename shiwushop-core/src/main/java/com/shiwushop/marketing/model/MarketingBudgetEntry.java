package com.shiwushop.marketing.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class MarketingBudgetEntry {
    /** 预算流水 ID。 */
    private Long id;
    /** 营销预算 ID。 */
    private Long budgetId;
    /** 账本操作幂等键，防止重复记账。 */
    private String operationKey;
    /** 账本事件类型。 */
    private String eventKind;
    /** 本次占用余额变化量，单位元。 */
    private BigDecimal heldDelta;
    /** 本次预算支出增减金额，单位元。 */
    private BigDecimal spentDelta;
    /** 本次记账后的占用余额，单位元。 */
    private BigDecimal heldAfter;
    /** 本次记账后的预算已支出金额，单位元。 */
    private BigDecimal spentAfter;
    /** 关联订单 ID。 */
    private Long orderId;
    /** 关联售后单 ID。 */
    private Long afterSaleCaseId;
    /** 本次操作人用户 ID。 */
    private Long actorUserId;
    /** 本次业务操作的原因。 */
    private String reason;
    /** 本次预算总额度的增减金额，单位元。 */
    private BigDecimal budgetDeltaAmount;
    /** 本次操作后的预算总额度，单位元。 */
    private BigDecimal budgetAfter;
    /** 操作凭据引用。 */
    private String evidenceReference;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
}
