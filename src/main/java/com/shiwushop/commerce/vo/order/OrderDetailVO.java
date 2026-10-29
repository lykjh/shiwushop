package com.shiwushop.commerce.vo.order;

import java.math.BigDecimal;
import java.util.List;

public record OrderDetailVO(Long id, Long shopId, String shopName, String status,
                            BigDecimal totalAmount, AddressSnapshot addressSnapshot,
                            List<OrderItemVO> items) {
    public record AddressSnapshot(String consignee, String phone, String address) {}
}
