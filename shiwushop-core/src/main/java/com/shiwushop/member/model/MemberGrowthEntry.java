package com.shiwushop.member.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class MemberGrowthEntry {
    /** 成长账本流水 ID。 */
    private Long id;
    /** 所属用户 ID。 */
    private Long userId;
    /** 关联订单明细 ID。 */
    private Long orderItemId;
    /** 关联售后单 ID。 */
    private Long afterSaleCaseId;
    /** 被冲回的原成长入账流水 ID，用于退款按来源补差。 */
    private Long sourceEntryId;
    /** 账本事件类型。 */
    private String eventKind;
    /** 账本操作幂等键，防止重复记账。 */
    private String operationKey;
    /** 本次调整的金额，正数增加、负数扣减，单位元。 */
    private BigDecimal deltaAmount;
    /** 本次变动后的余额，单位元。 */
    private BigDecimal balanceAfter;
    /** 变动前会员等级代码。 */
    private String oldLevelCode;
    /** 变动后会员等级代码。 */
    private String newLevelCode;
    /** 本次操作人用户 ID。 */
    private Long actorUserId;
    /** 本次业务操作的原因。 */
    private String reason;
    /** 操作凭据引用。 */
    private String evidenceReference;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
}
