package com.shiwushop.slice.commerce;

import com.shiwushop.aftersale.controller.AfterSaleController;
import com.shiwushop.aftersale.service.AfterSaleService;
import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.support.ControllerSliceSecurity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AfterSaleController.class)
@ContextConfiguration(classes = {AfterSaleController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class AfterSaleControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AfterSaleService service;

    @ParameterizedTest
    @ValueSource(strings = {"{\"type\":\"UNKNOWN\",\"reason\":\"商品损坏\",\"items\":[{\"orderItemId\":1,\"quantity\":1}]}",
            "{\"type\":\"REFUND_ONLY\",\"reason\":\"商品损坏\",\"items\":[null]}",
            "{\"type\":\"REFUND_ONLY\",\"reason\":\"商品损坏\",\"items\":[{\"orderItemId\":1,\"quantity\":0}]}"})
    void rejectsInvalidItemsBeforeBusinessService(String body) throws Exception {
        mvc.perform(post("/orders/1/after-sales").header("Idempotency-Key", "after-sale-zhang")
                        .with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"approved\":false}", "{\"approved\":false,\"reason\":\" \"}", "{\"reason\":\"商品损坏\"}"})
    void rejectionMustIncludeAReason(String body) throws Exception {
        mvc.perform(post("/merchant/shops/1/after-sales/1/review").with(csrf())
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number", "9223372036854775808"})
    void rejectsInvalidWithdrawalId(String id) throws Exception {
        mvc.perform(post("/after-sales/" + id + "/withdraw").with(csrf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
