package com.shiwushop.slice.aftersale;

import com.shiwushop.aftersale.controller.AdminAfterSaleTimeoutController;
import com.shiwushop.aftersale.service.AfterSaleTimeoutService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminAfterSaleTimeoutController.class)
@ContextConfiguration(classes = {AdminAfterSaleTimeoutController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class AdminAfterSaleTimeoutControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AfterSaleTimeoutService service;

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidId(String id) throws Exception {
        String path = "/admin/after-sale-timeouts/" + id;
        mvc.perform(get(path)).andExpect(status().isBadRequest());
        mvc.perform(post(path + "/claim").with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post(path + "/close").with(csrf()).contentType("application/json")
                .content("{\"evidence\":\"记录\",\"conclusion\":\"完成\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "abc"})
    void rejectsInvalidSize(String size) throws Exception {
        mvc.perform(get("/admin/after-sale-timeouts").param("size", size)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"evidence\":\"\",\"conclusion\":\"完成\"}",
            "{\"evidence\":\"记录\",\"conclusion\":\" \"}"})
    void rejectsIncompleteClose(String body) throws Exception {
        mvc.perform(post("/admin/after-sale-timeouts/1/close").with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
