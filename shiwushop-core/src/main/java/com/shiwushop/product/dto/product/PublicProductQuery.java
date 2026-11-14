package com.shiwushop.product.dto.product;

import java.math.BigDecimal;

public record PublicProductQuery(
    /** 分类 ID。 */
    Long categoryId,
    /** 品牌 ID。 */
    Long brandId,
    /** 所属店铺 ID。 */
    Long shopId,
    /** 价格筛选下限，单位元。 */
    BigDecimal minPrice,
    /** 价格筛选上限，单位元。 */
    BigDecimal maxPrice,
    /** 每页请求条数。 */
    int size,
    /** 本页查询的起始 ID 游标。 */
    Long afterId
) {
}
