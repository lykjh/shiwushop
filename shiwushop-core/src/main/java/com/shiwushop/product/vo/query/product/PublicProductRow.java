package com.shiwushop.product.vo.query.product;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PublicProductRow {
    /** 当前记录 ID。 */
    private Long id;
    /** 名称。 */
    private String name;
    /** 商品封面媒体 ID。 */
    private Long coverMediaId;
    /** 品牌 ID。 */
    private Long brandId;
    /** 品牌名称。 */
    private String brandName;
    /** 所属店铺 ID。 */
    private Long shopId;
    /** 店铺名称。 */
    private String shopName;
    /** 商品当前可展示 SKU 的起售价，单位元。 */
    private BigDecimal startingPrice;
    /** 已统计的销量件数。 */
    private Long soldCount;
    /** 是否无可售库存。 */
    private Boolean soldOut;
}
