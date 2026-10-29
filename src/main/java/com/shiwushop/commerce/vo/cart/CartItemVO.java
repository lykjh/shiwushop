package com.shiwushop.commerce.vo.cart;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CartItemVO {
    private Long id;
    private Long skuId;
    private Long productId;
    private String productName;
    private List<String> optionValues;
    private BigDecimal price;
    private Integer quantity;
    private Boolean available;
    private String unavailableReason;
}
