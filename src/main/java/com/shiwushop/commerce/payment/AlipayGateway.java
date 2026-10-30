package com.shiwushop.commerce.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public interface AlipayGateway {
    /** 判断当前配置是否允许安全发起渠道请求。 */
    boolean configured();
    /** 返回支付应用身份，用于通知和本地记录核对。 */
    String appId();
    /** 返回收款商户身份。 */
    String sellerId();
    /** 区分正式渠道与沙箱渠道。 */
    String channel();
    /** 为指定商户订单申请在订单截止前有效的支付二维码。 */
    QrCode qrCode(String outTradeNo, BigDecimal amount, LocalDateTime expiresAt);
    /** 校验支付宝异步通知签名。 */
    boolean verify(Map<String, String> form);
    /** 查询商户订单在渠道的交易状态。 */
    TradeResult query(String outTradeNo);
    /** 请求渠道关闭待付款交易。 */
    boolean close(String outTradeNo);
    /** 撤销交易并区分已关闭、无交易和未决状态。 */
    CancelResult cancel(String outTradeNo);

    enum CancelResult { CLOSED, NO_TRADE, UNRESOLVED }

    record QrCode(String value, LocalDateTime expiresAt) {}

    record TradeResult(String status, String outTradeNo, String tradeNo, BigDecimal amount) {}
}
