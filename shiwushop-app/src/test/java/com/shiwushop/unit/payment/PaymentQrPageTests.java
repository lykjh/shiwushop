package com.shiwushop.unit.payment;

import com.shiwushop.common.BusinessException;
import com.shiwushop.payment.view.PaymentQrPage;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

/** 独立验证 Web 收银页渲染，交易服务的测试只验证付款数据及事务行为。 */
class PaymentQrPageTests {
    @Test void rendersSandboxQrAmountAndDeadline() {
        String html = PaymentQrPage.render("https://qr.alipay.com/payment", new BigDecimal("12.50"),
                LocalDateTime.of(2026, 11, 2, 18, 0), "ALIPAY_SANDBOX");
        assertTrue(html.contains("支付宝沙箱扫码支付")); assertTrue(html.contains("<svg"));
        assertTrue(html.contains("¥12.50")); assertTrue(html.contains("2026-11-02 18:00:00"));
        assertFalse(html.contains("https://qr.alipay.com/payment"), "二维码内容只编码进图形，不直接拼入 HTML");
    }

    @Test void productionPageUsesProductionInstructions() {
        String html = PaymentQrPage.render("https://qr.alipay.com/payment", BigDecimal.ONE,
                LocalDateTime.of(2026, 11, 2, 18, 0), "ALIPAY");
        assertTrue(html.contains("请使用支付宝 App 扫码付款")); assertFalse(html.contains("沙箱"));
    }

    @Test void unencodableQrContentReturnsServiceUnavailable() {
        var error = assertThrows(BusinessException.class, () -> PaymentQrPage.render("A".repeat(5000), BigDecimal.ONE,
                LocalDateTime.of(2026, 11, 2, 18, 0), "ALIPAY"));
        assertEquals(503, error.getCode());
    }
}
