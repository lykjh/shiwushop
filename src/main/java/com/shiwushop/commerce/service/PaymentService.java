package com.shiwushop.commerce.service;

import com.shiwushop.commerce.vo.payment.PaymentStartVO;
import com.shiwushop.commerce.vo.payment.PaymentStatusVO;

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
