package com.shiwushop.product.vo.product;

import com.shiwushop.product.entity.brand.Brand;
import com.shiwushop.merchant.entity.shop.Shop;

import java.math.BigDecimal;

public record PublicProductVO(
    /** 当前记录 ID。 */
    Long id,
    /** 名称。 */
    String name,
    /** 商品封面媒体 ID。 */
    Long coverMediaId,
    /** 品牌展示信息。 */
    Brand brand,
    /** 店铺展示信息。 */
    Shop shop,
    /** 商品当前可展示 SKU 的起售价，单位元。 */
    BigDecimal startingPrice,
    /** 已统计的销量件数。 */
    Long soldCount,
    /** 是否无可售库存。 */
    boolean soldOut
) {

    public record Brand(
        /** 当前记录 ID。 */
        Long id,
        /** 名称。 */
        String name
    ) {
    }

    public record Shop(
        /** 当前记录 ID。 */
        Long id,
        /** 名称。 */
        String name
    ) {
    }
}
