package com.shiwushop.marketing.vo;

import com.shiwushop.marketing.model.MarketingBudget;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record BudgetVO(
    /** 当前记录 ID。 */
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
    /** 可用预算：总额度减占用及已支出，单位元。 */
    BigDecimal availableAmount,
    /** 商家协议中的平台承担基点数；平台预算行为空，10000 表示 100%。 */
    Integer platformShareBp,
    /** 预算承担协议确认时间。 */
    OffsetDateTime agreedAt,
    /** 并发更新版本号。 */
    Long version
) {
    public static BudgetVO from(MarketingBudget budget) {
        return new BudgetVO(budget.id(), budget.activityId(), budget.party(), budget.shopId(), budget.budgetAmount(),
                budget.heldAmount(), budget.spentAmount(),
                budget.budgetAmount().subtract(budget.heldAmount()).subtract(budget.spentAmount()),
                budget.platformShareBp(),
                budget.agreedAt() == null ? null : budget.agreedAt().atOffset(ZoneOffset.ofHours(8)), budget.version());
    }
}
