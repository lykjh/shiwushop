package com.shiwushop.slice.payment;

import com.shiwushop.support.ControllerSliceSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.trade.controller.payment.PaymentController;
import com.shiwushop.trade.service.OrderPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@ContextConfiguration(classes = {PaymentController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class PaymentCheckoutControllerSliceTests {
    @MockitoBean
    private OrderPaymentService payments;
    @Autowired
    private MockMvc mvc;

    @Test
    void databaseFailureReturnsRetryableCallbackFailure() throws Exception {
        org.mockito.Mockito.doThrow(
                        new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
                .when(payments).notifyPaid(org.mockito.ArgumentMatchers.anyMap());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/payments/alipay/notify")
                        .with(csrf().asHeader()).contentType("application/x-www-form-urlencoded").param("out_trade_no", "SHIWUSHOP1"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string("failure"));
    }

    @Test
    void programmingErrorIsNotReportedAsInvalidCallbackParameters() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("internal defect"))
                .when(payments).notifyPaid(org.mockito.ArgumentMatchers.anyMap());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/payments/alipay/notify")
                        .with(csrf().asHeader()).contentType("application/x-www-form-urlencoded").param("out_trade_no", "SHIWUSHOP1"))
                .andExpect(status().isInternalServerError()).andExpect(content().string("failure"));
    }

    @Test
    void servesQrPageWithoutCaching() throws Exception {
        when(payments.checkoutPage("SHIWUSHOP1")).thenReturn(new com.shiwushop.trade.vo.payment.PaymentCheckoutVO(
                "https://qr.alipay.com/checkout", new java.math.BigDecimal("12.50"),
                java.time.LocalDateTime.of(2026, 11, 2, 18, 0), "ALIPAY_SANDBOX"));
        mvc.perform(get("/payments/checkout/SHIWUSHOP1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<svg")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("¥12.50")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-11-02 18:00:00")));
    }

    @Test
    void expiredCheckoutLinkReturnsBusinessStatusForBrowserNavigation() throws Exception {
        when(payments.checkoutPage("SHIWUSHOP1"))
                .thenThrow(new BusinessException(404, "支付记录不存在"));
        mvc.perform(get("/payments/checkout/SHIWUSHOP1").accept("text/html"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string(containsString("请返回商城查看订单和支付状态。")));
    }

    @Test
    void reportsActiveClaimAsProcessingConflict() throws Exception {
        when(payments.checkoutPage("SHIWUSHOP1"))
                .thenThrow(new BusinessException(409, "二维码生成中，请稍后重试"));
        mvc.perform(get("/payments/checkout/SHIWUSHOP1"))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("二维码生成中，请稍后重试")));
    }
}
