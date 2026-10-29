package com.shiwushop.catalog.vo.query.product;

import lombok.Data;

@Data
public class SkuOptionValueRow {
    private Long skuId;
    private Long valueId;
    private String valueName;
}
