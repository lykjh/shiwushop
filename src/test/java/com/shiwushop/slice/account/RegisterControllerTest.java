package com.shiwushop.slice.account;

import com.shiwushop.user.controller.user.RegisterController;
import com.shiwushop.user.auth.AuthRiskLimiter;
import com.shiwushop.user.service.RegisterService;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.user.auth.SessionValidationFilter;
import com.shiwushop.common.auth.WebSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 仅加载注册 Controller 和 HTTP 安全链；服务替身不触碰持久依赖。 */
@WebMvcTest(controllers = RegisterController.class, excludeFilters =
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SessionValidationFilter.class))
@Import({WebSecurityConfig.class, RegisterControllerTest.Security.class})
@ContextConfiguration(classes = {RegisterController.class, GlobalExceptionHandler.class})
class RegisterControllerTest {
    // Boot 4 的 MVC 切片不自动导入安全模块，显式启用实际安全链。
    @TestConfiguration @EnableWebSecurity static class Security {}
    @Autowired MockMvc mvc;
    @MockitoBean RegisterService service;
    @MockitoBean AuthRiskLimiter risk;
    private static final String VALID = "{\"username\":\"buyer_zhang\",\"password\":\"Shop123!\"}";

    @Test void validBodyDelegatesAndDoesNotExposeCredentials() throws Exception {
        mvc.perform(post("/users/register").with(csrf()).contentType("application/json").content(VALID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        verify(service).register(any(), any());
        verify(risk).recordRegisterSuccess(any());
    }
    @Test void missingCsrfDoesNotReachBusinessService() throws Exception {
        mvc.perform(post("/users/register").contentType("application/json").content(VALID))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service, risk);
    }
    @Test void invalidFieldsDoNotReachBusinessService() throws Exception {
        mvc.perform(post("/users/register").with(csrf()).contentType("application/json")
                        .content("{\"username\":\"abc\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.fields.username").isArray())
                .andExpect(jsonPath("$.data.fields.password").isArray());
        verifyNoInteractions(service, risk);
    }
    @Test void malformedJsonDoesNotReachBusinessService() throws Exception {
        mvc.perform(post("/users/register").with(csrf()).contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, risk);
    }
    @Test void captchaChallengePreservesHttpAndBusinessState() throws Exception {
        doThrow(new BusinessException(403, "请先完成滑块验证", Map.of("state", "CAPTCHA_REQUIRED"), null))
                .when(service).register(any(), any());
        mvc.perform(post("/users/register").with(csrf()).contentType("application/json").content(VALID))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data.state").value("CAPTCHA_REQUIRED"));
        verifyNoInteractions(risk);
    }
    @Test void rateLimitPublishesRetryAfter() throws Exception {
        doThrow(new BusinessException(429, "请求过于频繁", Map.of("retryAfterSeconds", 2), null))
                .when(service).register(any(), any());
        mvc.perform(post("/users/register").with(csrf()).contentType("application/json").content(VALID))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "2"));
        verifyNoInteractions(risk);
    }
}
