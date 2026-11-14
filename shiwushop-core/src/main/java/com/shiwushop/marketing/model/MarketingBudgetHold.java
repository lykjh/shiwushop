package com.shiwushop.marketing.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class MarketingBudgetHold {
    /** 预算占用 ID。 */
    private Long id;
    /** 营销预算 ID。 */
    private Long budgetId;
    /** 账本操作幂等键，防止重复记账。 */
    private String operationKey;
    /** 占用来源类型：COUPON 券、ORDER 订单、GROUP 拼团、BARGAIN 砍价、POINTS 兑换。 */
    private String sourceKind;
    /** 来源业务记录 ID，与 sourceKind 配合定位。 */
    private Long sourceId;
    /** 来源预算占用 ID，用于追溯占用转移关系。 */
    private Long parentHoldId;
    /** 该笔预算占用被授权承担的总金额，单位元。 */
    private BigDecimal authorizedAmount;
    /** 当前已占用、尚未转支出的预算金额，单位元。 */
    private BigDecimal heldAmount;
    /** 当前已转为支出的预算金额，单位元。 */
    private BigDecimal spentAmount;
    /** 该笔预算占用累计释放的金额，单位元。 */
    private BigDecimal releasedAmount;
    /** 该占用来源已冲回的预算支出金额，单位元。 */
    private BigDecimal refundedAmount;
    /** 有效截止时间。 */
    private LocalDateTime expiresAt;
    /** 预算占用状态：HELD 占用、PARTIAL 部分转支出、SPENT 已支出、RELEASED 已释放。 */
    private String status;
}
