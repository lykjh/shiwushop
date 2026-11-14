package com.shiwushop.marketing.vo;

public record StockPoolVO(
    /** 活动商品范围记录 ID，不是 SKU ID。 */
    long activitySkuId,
    /** 活动库存池 ID。 */
    long poolId,
    /** 商品规格（SKU）ID。 */
    long skuId,
    /** 累计划入活动库存池的件数。 */
    int allocatedQuantity,
    /** 活动库存池中可用件数。 */
    int availableQuantity,
    /** 活动参与或订单已占用的库存件数。 */
    int heldQuantity,
    /** 活动库存池中累计已消耗件数。 */
    int consumedQuantity,
    /** 已从活动库存池退回普通库存的件数。 */
    int returnedToNormalQuantity
) {
    public record Transfer(
        /** 变动后的库存池信息。 */
        StockPoolVO pool,
        /** 活动库存变动流水 ID。 */
        long stockEntryId
    ) {
    }
}
