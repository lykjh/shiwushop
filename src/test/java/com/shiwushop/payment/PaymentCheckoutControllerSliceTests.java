package com.shiwushop.payment;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.controller.payment.PaymentController;
import com.shiwushop.service.PaymentService;
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

class PaymentCheckoutControllerSliceTests {
    private final PaymentService payments = mock(PaymentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PaymentController(payments))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

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
