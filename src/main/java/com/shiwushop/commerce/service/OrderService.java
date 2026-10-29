package com.shiwushop.commerce.service;

import com.shiwushop.commerce.vo.order.OrderSliceVO;
import com.shiwushop.commerce.vo.order.OrderDetailVO;



public interface OrderService {
    OrderDetailVO detail(Long userId, Long orderId);

    OrderSliceVO list(Long userId, Long afterId, int size);

    OrderSliceVO listMerchant(Long userId, Long shopId, Long afterId, int size);

    OrderDetailVO detailMerchant(Long userId, Long shopId, Long orderId);
}
