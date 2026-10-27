package com.shiwushop.vo.product;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ProductOptionVO {
    private Long id;
    private String name;
    private List<ProductOptionValueVO> values;
}
