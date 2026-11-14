package com.shiwushop.member.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MemberPointLot {
    /** 积分批次 ID。 */
    private Long id;
    /** 所属用户 ID。 */
    private Long userId;
    /** 来源业务的幂等标识。 */
    private String sourceKey;
    /** 关联订单明细 ID。 */
    private Long orderItemId;
    /** 本积分批次累计发放的最小单位数；100 单位为 1 积分。 */
    private long issuedUnits;
    /** 本积分批次尚可使用的最小单位数；100 单位为 1 积分。 */
    private long availableUnits;
    /** 本积分批次当前占用的最小单位数；100 单位为 1 积分。 */
    private long heldUnits;
    /** 本积分批次已消耗的最小单位数；100 单位为 1 积分。 */
    private long spentUnits;
    /** 本积分批次已过期的最小单位数；100 单位为 1 积分。 */
    private long expiredUnits;
    /** 本积分批次已冲回的最小单位数；100 单位为 1 积分。 */
    private long reversedUnits;
    /** 本积分批次已用于抵扣历史欠额的最小单位数；100 单位为 1 积分。 */
    private long debtOffsetUnits;
    /** 积分批次首次入账时间。 */
    private LocalDateTime earnedAt;
    /** 有效截止时间。 */
    private LocalDateTime expiresAt;
    /** 积分批次状态：ACTIVE 有余额、EXHAUSTED 已耗尽、EXPIRED 过期。 */
    private String status;
}
