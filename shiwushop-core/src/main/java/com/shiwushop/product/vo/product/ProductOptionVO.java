package com.shiwushop.product.vo.product;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ProductOptionVO {
    /** 规格选项 ID。 */
    private Long id;
    /** 规格选项名称。 */
    private String name;
    /** 规格选项值列表。 */
    private List<ProductOptionValueVO> values;
}
