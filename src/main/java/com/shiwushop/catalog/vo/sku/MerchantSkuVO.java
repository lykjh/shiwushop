package com.shiwushop.catalog.vo.sku;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
public class MerchantSkuVO {
    private Long id;
    private Long productId;
    private List<Long> optionValueIds;
    private BigDecimal price;
    private Integer stock;
    private Boolean legacy;
}
