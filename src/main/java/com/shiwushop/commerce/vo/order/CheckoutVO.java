package com.shiwushop.commerce.vo.order;

import java.math.BigDecimal;
import java.util.List;

public record CheckoutVO(List<PlacedOrder> orders, BigDecimal totalAmount) {
    public record PlacedOrder(Long id, Long shopId, BigDecimal totalAmount, String status) {}
}
