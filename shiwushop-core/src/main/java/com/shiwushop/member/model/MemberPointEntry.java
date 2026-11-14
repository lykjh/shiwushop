package com.shiwushop.member.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MemberPointEntry {
    /** 积分账本流水 ID。 */
    private Long id;
    /** 所属用户 ID。 */
    private Long userId;
    /** 账本事件类型。 */
    private String eventKind;
    /** 账本操作幂等键，防止重复记账。 */
    private String operationKey;
    /** 关联订单 ID。 */
    private Long orderId;
    /** 关联售后单 ID。 */
    private Long afterSaleCaseId;
    /** 本次可用余额变化量，以积分最小单位计；100 单位为 1 积分。 */
    private long availableDelta;
    /** 本次占用余额变化量，以积分最小单位计；100 单位为 1 积分。 */
    private long heldDelta;
    /** 本次积分欠额增减的最小单位数，以积分最小单位计；100 单位为 1 积分。 */
    private long debtDelta;
    /** 本次记账后的可用积分最小单位数；100 单位为 1 积分。 */
    private long availableAfter;
    /** 本次记账后的占用余额，以积分最小单位计；100 单位为 1 积分。 */
    private long heldAfter;
    /** 本次记账后的积分欠额最小单位数；100 单位为 1 积分。 */
    private long debtAfter;
    /** 本次操作人用户 ID。 */
    private Long actorUserId;
    /** 本次业务操作的原因。 */
    private String reason;
    /** 操作凭据引用。 */
    private String evidenceReference;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
}
