package com.shiwushop.slice.payment;

import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.support.ControllerSliceSecurity;
import com.shiwushop.payment.controller.AdminReconciliationIssueController;
import com.shiwushop.common.auth.AdminAuth;
import com.shiwushop.payment.service.ReconciliationIssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import org.springframework.http.MediaType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminReconciliationIssueController.class)
@ContextConfiguration(classes = {AdminReconciliationIssueController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class ReconciliationIssueControllerSliceTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private ReconciliationIssueService issues;
    @MockitoBean private AdminAuth auth;

    @Test
    void administratorCanClaimExistingIssue() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 7L);
        when(auth.requireAdmin(any())).thenReturn(7L);
        mvc.perform(post("/admin/payment-reconciliation/issues/10/claim").session(session).with(csrf()))
                .andExpect(status().isOk());
    }
    @Test
    void rejectsInvalidEvidenceAndConclusionAtHttpBoundary() throws Exception {
        when(auth.requireAdmin(any())).thenReturn(7L);
        String root="/admin/payment-reconciliation/issues/10";
        mvc.perform(post(root+"/evidence").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"billEvidence\":\"  \",\"localEvidence\":\"x\",\"channelQueryEvidence\":\"y\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(root+"/resolve").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"evidenceId\":1,\"conclusionType\":\"OTHER\",\"conclusion\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/payment-reconciliation/issues/0/claim").with(csrf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(issues);
    }
}
