package com.shiwushop.service;

import com.shiwushop.vo.order.OrderSliceVO;
import com.shiwushop.vo.order.OrderDetailVO;



public interface OrderService {
    OrderDetailVO detail(Long userId, Long orderId);

    OrderSliceVO list(Long userId, Long afterId, int size);

    OrderSliceVO listMerchant(Long userId, Long shopId, Long afterId, int size);

    OrderDetailVO detailMerchant(Long userId, Long shopId, Long orderId);
}
