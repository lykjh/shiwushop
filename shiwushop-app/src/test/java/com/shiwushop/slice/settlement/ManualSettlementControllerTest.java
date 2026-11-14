package com.shiwushop.slice.settlement;

import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.support.ControllerSliceSecurity;
import com.shiwushop.trade.controller.settlement.ManualSettlementController;
import com.shiwushop.trade.settlement.ManualSettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.shiwushop.support.MockLogin.loginAs;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ManualSettlementController.class)
@ContextConfiguration(classes = {ManualSettlementController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class ManualSettlementControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ManualSettlementService service;

    @Test
    void sessionAndCsrfGuardMutations() throws Exception {
        String body = "{\"requestKey\":\"create-1\",\"shopId\":7,\"payeeUserId\":9,"
                + "\"orders\":[{\"orderId\":11,\"ownershipEvidence\":\"signed claim\"}]}";
        mvc.perform(post("/admin/manual-settlements").with(loginAs(1L))
                        .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/manual-settlements").with(csrf().asHeader())
                        .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        mvc.perform(post("/admin/manual-settlements").with(loginAs(1L)).with(csrf().asHeader())
                        .contentType("application/json").content(body)).andExpect(status().isOk());
        verify(service).create(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMissingPayeeEvidenceAndImpreciseMoney() throws Exception {
        String create = "{\"requestKey\":\"a\",\"shopId\":7,\"payeeUserId\":9,"
                + "\"orders\":[{\"orderId\":11,\"ownershipEvidence\":\" \"}]}";
        mvc.perform(post("/admin/manual-settlements").with(loginAs(1L)).with(csrf().asHeader())
                        .contentType("application/json").content(create)).andExpect(status().isBadRequest());
        String payment = "{\"requestKey\":\"p\",\"actualAmount\":1.001,"
                + "\"paidAt\":\"2026-11-07T00:00:00\",\"method\":\"bank\",\"payeeUserId\":9}";
        mvc.perform(post("/admin/manual-settlements/1/payment").with(loginAs(1L)).with(csrf().asHeader())
                        .contentType("application/json").content(payment)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void merchantScopeComesFromSession() throws Exception {
        mvc.perform(get("/merchant/manual-settlements/12").with(loginAs(9L)))
                .andExpect(status().isOk());
        verify(service).merchantDetail(9L, 12L);
    }
}
