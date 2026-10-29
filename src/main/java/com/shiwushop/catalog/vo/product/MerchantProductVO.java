package com.shiwushop.catalog.vo.product;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MerchantProductVO {
    private Long id;
    private Long shopId;
    private Long categoryId;
    private Long brandId;
    private String name;
    private String status;
}
