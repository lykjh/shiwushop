package com.shiwushop.vo.order;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderSummaryVO {
    private Long id;
    private Long shopId;
    private String shopName;
    private BigDecimal totalAmount;
    private String status;
    private Long itemCount;
}
