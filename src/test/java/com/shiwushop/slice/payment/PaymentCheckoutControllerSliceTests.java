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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@ContextConfiguration(classes={PaymentController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class PaymentCheckoutControllerSliceTests {
    @MockitoBean private OrderPaymentService payments;
    @Autowired private MockMvc mvc;

    @Test
    void databaseFailureReturnsRetryableCallbackFailure() throws Exception {
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
                .when(payments).notifyPaid(org.mockito.ArgumentMatchers.anyMap());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/payments/alipay/notify")
                        .with(csrf()).contentType("application/x-www-form-urlencoded").param("out_trade_no", "SHIWUSHOP1"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string("failure"));
    }

    @Test
    void programmingErrorIsNotReportedAsInvalidCallbackParameters() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("internal defect"))
                .when(payments).notifyPaid(org.mockito.ArgumentMatchers.anyMap());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/payments/alipay/notify")
                        .with(csrf()).contentType("application/x-www-form-urlencoded").param("out_trade_no", "SHIWUSHOP1"))
                .andExpect(status().isInternalServerError()).andExpect(content().string("failure"));
    }

    @Test
    void servesQrPageWithoutCaching() throws Exception {
        when(payments.checkoutPage("SHIWUSHOP1")).thenReturn("<html>qr</html>");
        mvc.perform(get("/payments/checkout/SHIWUSHOP1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string("<html>qr</html>"));
    }

    @Test
    void reportsActiveClaimAsProcessingConflict() throws Exception {
        when(payments.checkoutPage("SHIWUSHOP1"))
                .thenThrow(new BusinessException(409, "二维码生成中，请稍后重试"));
        mvc.perform(get("/payments/checkout/SHIWUSHOP1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("二维码生成中，请稍后重试"));
    }
}
