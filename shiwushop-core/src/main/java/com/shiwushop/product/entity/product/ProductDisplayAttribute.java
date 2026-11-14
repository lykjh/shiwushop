package com.shiwushop.product.entity.product;

import lombok.Data;

@Data
public class ProductDisplayAttribute {
    /** 商品（SPU）ID。 */
    private Long productId;
    /** 展示属性名称。 */
    private String attributeKey;
    /** 展示属性值。 */
    private String attributeValue;
    /** 展示顺序，范围 0 至 19。 */
    private Integer position;
}
