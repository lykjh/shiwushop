package com.shiwushop.marketing.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class CouponGrant {
    /** 发券义务 ID。 */
    private Long id;
    /** 所属用户 ID。 */
    private Long userId;
    /** 关联营销活动 ID。 */
    private Long activityId;
    /** 发券来源：MEMBERSHIP 月券、CLAIM 领取、CODE 兑换、COMPENSATION 补偿。 */
    private String sourceKind;
    /** 来源业务的幂等标识。 */
    private String sourceKey;
    /** 月券权益所属月份，以该月第一天存储；非月券为空。 */
    private LocalDate benefitMonth;
    /** 权益判定时冻结的会员等级代码。 */
    private String eligibleLevelCode;
    /** 本次发券义务应发的总张数。 */
    private int entitledCount;
    /** 单张优惠券面额，单位元。 */
    private BigDecimal faceAmount;
    /** 享受该优惠所需的门槛金额，单位元。 */
    private BigDecimal thresholdAmount;
    /** 发券义务冻结的券到期时间；重试不延长。 */
    private LocalDateTime expiresAt;
    /** 发券状态：PENDING 待发、GRANTED 已发、EXPIRED 过期、ATTENTION 待处理。 */
    private String status;
    /** 任务允许再次尝试的时间。 */
    private LocalDateTime nextAttemptAt;
    /** 本次发券失败的错误代码。 */
    private String errorCode;
    /** 本次发券使用的兑换码记录 ID。 */
    private Long codeId;
    /** 原请求内容哈希，用于核对同键重放是否改变参数。 */
    private byte[] requestHash;
    /** 人工补偿发券的操作人用户 ID。 */
    private Long actorUserId;
    /** 本次业务操作的原因。 */
    private String reason;
    /** 操作凭据引用。 */
    private String evidenceReference;
}
