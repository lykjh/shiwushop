package com.shiwushop.product.vo.sku;

public record SkuStatusVO(
    /** 商品规格（SKU）ID。 */
    Long skuId,
    /** SKU 状态：ACTIVE 可销售、STOPPED 停售。 */
    String status
) {
}
