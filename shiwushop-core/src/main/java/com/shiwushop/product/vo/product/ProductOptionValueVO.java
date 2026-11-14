package com.shiwushop.product.vo.product;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ProductOptionValueVO {
    /** 规格选项值 ID。 */
    private Long id;
    /** 展示属性值。 */
    private String value;
}
