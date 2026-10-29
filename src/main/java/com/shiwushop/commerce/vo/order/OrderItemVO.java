package com.shiwushop.commerce.vo.order;

import java.math.BigDecimal;

public record OrderItemVO(Long orderItemId, Long productId, Long skuId, String productName, String spec,
                          Integer quantity, BigDecimal unitPrice, BigDecimal lineAmount) {}
