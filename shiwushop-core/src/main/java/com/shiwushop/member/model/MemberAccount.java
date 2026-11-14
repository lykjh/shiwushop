package com.shiwushop.member.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class MemberAccount {
    /** 所属用户 ID。 */
    private Long userId;
    /** 会员等级代码。 */
    private String levelCode;
    /** 账户累计成长金额，保留角分精度，单位元。 */
    private BigDecimal growthAmount;
    /** 可用积分最小单位数；100 单位为 1 积分。 */
    private long availablePointUnits;
    /** 已占用且暂不可另行使用的积分最小单位数；100 单位为 1 积分。 */
    private long heldPointUnits;
    /** 待由后续积分抵扣的欠额最小单位数；100 单位为 1 积分。 */
    private long pointDebtUnits;
    /** 会员账户建立时间。 */
    private LocalDateTime joinedAt;
    /** 会员权益开始累计的时间基准。 */
    private LocalDateTime rewardStartAt;
    /** 并发更新版本号。 */
    private long version;
}
