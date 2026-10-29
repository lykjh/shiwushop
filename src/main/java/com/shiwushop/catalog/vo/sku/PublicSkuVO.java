package com.shiwushop.catalog.vo.sku;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class PublicSkuVO {

    private Long id;
    private List<Long> optionValueIds;
    private BigDecimal price;
    private Boolean available;

}
