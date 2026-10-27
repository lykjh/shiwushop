package com.shiwushop.service;

import com.shiwushop.vo.payment.PaymentStartVO;
import com.shiwushop.vo.payment.PaymentStatusVO;

import java.util.Map;

public interface PaymentService {
    PaymentStartVO start(Long userId, Long orderId);
    String checkoutPage(String outTradeNo);
    PaymentStatusVO status(Long userId, Long orderId);
    PaymentStatusVO sync(Long userId, Long orderId);
    void notifyPaid(Map<String, String> form);
    void expireDueOrders();
    void expireOrder(Long orderId);
}
