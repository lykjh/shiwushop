package com.shiwushop.product.vo.stock;

/** 普通库存侧一次划拨的原始流水事实；重放返回首次的余额。 */
public record MarketingStockMovement(
    /** 普通库存变动流水 ID。 */
    Long movementId,
    /** 本次变动后的普通库存件数。 */
    int normalBalanceAfter,
    /** 普通库存本次增减件数。 */
    int normalDelta,
    /** 是否为幂等重放；重放返回原流水结果。 */
    boolean replayed
) {
}
