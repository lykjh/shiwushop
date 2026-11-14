package com.shiwushop.product.vo.query.product;

import lombok.Data;

@Data
public class SkuOptionValueRow {
    /** 商品规格（SKU）ID。 */
    private Long skuId;
    /** 规格选项值 ID。 */
    private Long valueId;
    /** 规格选项值名称。 */
    private String valueName;
}
