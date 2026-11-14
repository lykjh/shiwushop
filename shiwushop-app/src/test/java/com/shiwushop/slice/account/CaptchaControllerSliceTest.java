package com.shiwushop.slice.account;

import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.response.ApiResponse;
import com.shiwushop.common.captcha.controller.CaptchaController;
import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.common.auth.BrowserFlow;
import com.shiwushop.common.captcha.*;
import com.shiwushop.support.ControllerSliceSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

@WebMvcTest(CaptchaController.class)
@ContextConfiguration(classes = {CaptchaController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class CaptchaControllerSliceTest {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    CaptchaService service;
    @MockitoBean
    CaptchaRateLimiter limiter;
    @MockitoBean
    BrowserFlow browserFlow;

    @Test
    void generationKeepsTheExistingUserRoute() throws Exception {
        var captcha = new ImageCaptchaVO();
        captcha.setId("captcha-id");
        when(service.generateCaptcha()).thenReturn(ApiResponse.ofSuccess(captcha));
        mvc.perform(get("/users/generate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.id").value("captcha-id"));
        verify(browserFlow).getOrCreate(any(), any());
        verify(service).generateCaptcha();
    }

    @Test
    void missingTrackIsRejectedBeforeVerification() throws Exception {
        mvc.perform(
                        post("/users/valid").with(csrf().asHeader()).contentType("application/json").content("{\"id\":\"captcha-id\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, limiter);
    }

    @Test
    void oversizedCaptchaIdIsRejectedBeforeVerification() throws Exception {
        mvc.perform(post("/users/valid").with(csrf().asHeader()).contentType("application/json")
                        .content("{\"id\":\"" + "a".repeat(129) + "\",\"track\":{}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, limiter);
    }
}
