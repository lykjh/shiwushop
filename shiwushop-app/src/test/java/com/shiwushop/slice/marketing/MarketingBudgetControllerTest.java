package com.shiwushop.slice.marketing;

import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.marketing.controller.MarketingBudgetController;
import com.shiwushop.marketing.service.MarketingBudgetService;
import com.shiwushop.support.ControllerSliceSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MarketingBudgetController.class)
@ContextConfiguration(classes = {MarketingBudgetController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class MarketingBudgetControllerTest {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    MarketingBudgetService budgets;
    static final String BODY = "{\"deltaAmount\":1.25,\"reason\":\"增加预算\",\"evidenceRef\":\"E1\"}";

    @Test
    void routesDefineScopeAndIdentityComesOnlyFromSession() throws Exception {
        mvc.perform(get("/admin/marketing/activities/2/budgets").sessionAttr("userId", 1L).param("shopId", "99"))
                .andExpect(status().isOk());
        mvc.perform(get("/merchant/shops/7/marketing/activities/2/budgets").sessionAttr("userId", 1L))
                .andExpect(status().isOk());
        verify(budgets).list(1L, null, 2L);
        verify(budgets).list(1L, 7L, 2L);
    }

    @Test
    void adjustmentRequiresSessionAndCsrf() throws Exception {
        mvc.perform(post("/admin/marketing/budgets/3/adjustments").sessionAttr("userId", 1L)
                        .header("Idempotency-Key", "key").contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/marketing/budgets/3/adjustments").with(csrf())
                        .header("Idempotency-Key", "key").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(budgets);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "with space", "键", "a\u007fb"})
    void invalidKeysNeverReachService(String key) throws Exception {
        mvc.perform(post("/admin/marketing/budgets/3/adjustments").sessionAttr("userId", 1L).with(csrf())
                        .header("Idempotency-Key", key).contentType("application/json").content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(budgets);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "oops"})
    void pageBoundsAreValidatedAtController(String size) throws Exception {
        mvc.perform(get("/admin/marketing/budgets/3/entries").sessionAttr("userId", 1L).param("size", size))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(budgets);
    }

    @Test
    void decimalPrecisionAndEvidenceLengthAreNotSilentlyTruncated() throws Exception {
        for (String body : new String[]{BODY.replace("1.25", "1.251"), BODY.replace("E1", "X".repeat(501))}) {
            mvc.perform(post("/admin/marketing/budgets/3/adjustments").sessionAttr("userId", 1L).with(csrf())
                            .header("Idempotency-Key", "valid:~").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(budgets);
    }

    @Test
    void merchantAdjustmentPassesValidatedScopeAndVisibleKey() throws Exception {
        mvc.perform(post("/merchant/shops/7/marketing/budgets/3/adjustments").sessionAttr("userId", 1L).with(csrf())
                        .header("Idempotency-Key", "valid:~").contentType("application/json").content(BODY))
                .andExpect(status().isOk());
        verify(budgets).adjust(eq(1L), eq(7L), eq(3L), eq("valid:~"), any());
    }
}
