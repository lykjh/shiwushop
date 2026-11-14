package com.shiwushop.member.vo;

import java.time.OffsetDateTime;

public record MemberPointLotVO(
    /** 积分批次 ID。 */
    Long id,
    /** 本积分批次累计发放的最小单位数；100 单位为 1 积分。 */
    long issuedUnits,
    /** 本积分批次尚可使用的最小单位数；100 单位为 1 积分。 */
    long availableUnits,
    /** 本积分批次当前占用的最小单位数；100 单位为 1 积分。 */
    long heldUnits,
    /** 本积分批次已消耗的最小单位数；100 单位为 1 积分。 */
    long spentUnits,
    /** 本积分批次已过期的最小单位数；100 单位为 1 积分。 */
    long expiredUnits,
    /** 本积分批次已冲回的最小单位数；100 单位为 1 积分。 */
    long reversedUnits,
    /** 本积分批次已用于抵扣历史欠额的最小单位数；100 单位为 1 积分。 */
    long debtOffsetUnits,
    /** 积分批次首次入账时间。 */
    OffsetDateTime earnedAt,
    /** 有效截止时间。 */
    OffsetDateTime expiresAt,
    /** 积分批次状态：ACTIVE 有余额、EXHAUSTED 已耗尽、EXPIRED 过期。 */
    String status
) {
}
