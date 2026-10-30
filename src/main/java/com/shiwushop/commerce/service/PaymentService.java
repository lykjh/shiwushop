package com.shiwushop.commerce.service;

import com.shiwushop.commerce.vo.payment.PaymentStartVO;
import com.shiwushop.commerce.vo.payment.PaymentStatusVO;

import java.util.Map;

public interface PaymentService {
    /** 为本人订单创建或复用支付尝试并返回站内收银台地址。 */
    PaymentStartVO start(Long userId, Long orderId);
    /** 按商户订单号生成支付页面。 */
    String checkoutPage(String outTradeNo);
    /** 查询本人订单当前记录的支付状态。 */
    PaymentStatusVO status(Long userId, Long orderId);
    /** 主动查询支付网关并同步本人订单状态。 */
    PaymentStatusVO sync(Long userId, Long orderId);
    /** 验证支付平台异步通知，并幂等更新支付与订单状态。 */
    void notifyPaid(Map<String, String> form);
    /** 扫描到期未支付订单并逐条尝试关闭。 */
    void expireDueOrders();
    /** 对一笔到期订单执行关闭或与支付网关核对。 */
    void expireOrder(Long orderId);
}
