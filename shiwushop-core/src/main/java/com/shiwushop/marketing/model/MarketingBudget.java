package com.shiwushop.marketing.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MarketingBudget(
    /** 营销预算 ID。 */
    Long id,
    /** 关联营销活动 ID。 */
    Long activityId,
    /** 预算承担方：PLATFORM 平台、MERCHANT 商家。 */
    String party,
    /** 商家承担方店铺 ID；PLATFORM 预算为空。 */
    Long shopId,
    /** 预算总额度，单位元。 */
    BigDecimal budgetAmount,
    /** 当前已占用、尚未转支出的预算金额，单位元。 */
    BigDecimal heldAmount,
    /** 当前已转为支出的预算金额，单位元。 */
    BigDecimal spentAmount,
    /** 商家协议中的平台承担基点数；平台预算行为空，10000 表示 100%。 */
    Integer platformShareBp,
    /** 预算承担协议确认时间。 */
    LocalDateTime agreedAt,
    /** 并发更新版本号。 */
    Long version
) {
}
