package com.shiwushop.slice.account;

import com.shiwushop.common.captcha.controller.CaptchaController;
import com.shiwushop.common.GlobalExceptionHandler;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

@WebMvcTest(CaptchaController.class)
@ContextConfiguration(classes={CaptchaController.class,GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class CaptchaControllerSliceTest {
    @Autowired MockMvc mvc;@MockitoBean CaptchaService service;@MockitoBean CaptchaRateLimiter limiter;
    @Test void generationKeepsTheExistingUserRoute() throws Exception {mvc.perform(get("/users/generate")).andExpect(status().isOk());verify(service).generateCaptcha();}
    @Test void missingTrackIsRejectedBeforeVerification() throws Exception {
        mvc.perform(post("/users/valid").with(csrf()).contentType("application/json").content("{\"id\":\"captcha-id\"}"))
                .andExpect(status().isBadRequest());verifyNoInteractions(service,limiter);
    }
    @Test void oversizedCaptchaIdIsRejectedBeforeVerification() throws Exception {
        mvc.perform(post("/users/valid").with(csrf()).contentType("application/json")
                        .content("{\"id\":\"" + "a".repeat(129) + "\",\"track\":{}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, limiter);
    }
}
