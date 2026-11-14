package com.shiwushop.product.vo.product;

public record DisplayAttributeVO(
    /** 展示属性名称。 */
    String key,
    /** 展示属性值。 */
    String value,
    /** 展示顺序。 */
    int position
) {
}
