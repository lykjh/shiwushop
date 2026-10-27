package com.shiwushop.vo.query.product;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PublicProductRow {
    private Long id;
    private String name;
    private Long coverMediaId;
    private Long brandId;
    private String brandName;
    private Long shopId;
    private String shopName;
    private BigDecimal startingPrice;
    private Long soldCount;
    private Boolean soldOut;
}
