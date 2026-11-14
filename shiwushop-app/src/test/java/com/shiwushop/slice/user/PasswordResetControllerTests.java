package com.shiwushop.slice.user;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.common.auth.BrowserFlow;
import com.shiwushop.support.ControllerSliceSecurity;
import com.shiwushop.user.auth.JwtLogin;
import com.shiwushop.user.controller.user.PasswordResetController;
import com.shiwushop.user.service.PasswordResetService;
import com.shiwushop.user.vo.user.PasswordResetVerifyVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 切片只验证路由、入口约束、CSRF、响应契约和成功后的会话撤销。 */
@WebMvcTest(PasswordResetController.class)
@ContextConfiguration(classes = {PasswordResetController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class PasswordResetControllerTests {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    PasswordResetService service;
    @MockitoBean
    JwtLogin jwt;
    @MockitoBean
    BrowserFlow browserFlow;
    final ObjectMapper json = new ObjectMapper();
    final String ticket = "t".repeat(43);

    @Test
    void threeRoutesForwardInputsAndClearLoginOnlyAfterSuccess() throws Exception {
        mvc.perform(post("/users/password/reset/send").with(csrf().asHeader()).contentType("application/json")
                        .content("{\"phone\":\"13812345678\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        verify(service).sendCode(eq("13812345678"), any());
        when(service.verify(eq("13812345678"), eq("123456"), any()))
                .thenReturn(new PasswordResetVerifyVO(ticket, 300));
        mvc.perform(post("/users/password/reset/verify").with(csrf().asHeader()).contentType("application/json")
                        .content("{\"phone\":\"13812345678\",\"code\":\"123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resetTicket").value(ticket))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.data.id").doesNotExist());
        mvc.perform(post("/users/password/reset").with(csrf().asHeader()).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("resetTicket", ticket, "newPassword", "Shop456!"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        verify(service).reset(eq(ticket), eq("Shop456!"), any());
        verify(jwt).clearCookie(any());
        verify(browserFlow).rotate(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/users/password/reset/send", "/users/password/reset/verify", "/users/password/reset"})
    void everyWriteRequiresCsrf(String path) throws Exception {
        mvc.perform(post(path).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Shop12!", "asdfg密123456", "Shop😀123!", " Shop123!", "Shop123! ", ""})
    void invalidPasswordDoesNotReachService(String password) throws Exception {
        mvc.perform(post("/users/password/reset").with(csrf().asHeader()).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("resetTicket", ticket, "newPassword", password))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.fields.newPassword").exists());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"phone\":\"12812345678\"}", "{\"phone\":null}"})
    void invalidPhoneDoesNotSend(String body) throws Exception {
        mvc.perform(post("/users/password/reset/send").with(csrf().asHeader()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void invalidProofReturns401WithoutClearingLoginOrRotatingBrowserId() throws Exception {
        doThrow(new BusinessException(401, "重设凭证无效或已过期")).when(service)
                .reset(anyString(), anyString(), any());
        mvc.perform(post("/users/password/reset").with(csrf().asHeader()).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("resetTicket", ticket, "newPassword", "Shop456!"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(jwt, browserFlow);
    }

    @Test
    void rateLimitPreservesRetryAfter() throws Exception {
        doThrow(new BusinessException(429, "验证码发送过于频繁", Map.of("retryAfterSeconds", 60), null))
                .when(service).sendCode(anyString(), any());
        mvc.perform(post("/users/password/reset/send").with(csrf().asHeader()).contentType("application/json")
                        .content("{\"phone\":\"13812345678\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void missingBodyAndWrongMethodAreClientErrors() throws Exception {
        mvc.perform(post("/users/password/reset").with(csrf().asHeader()).contentType("application/json"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/users/password/reset/send")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }
}
