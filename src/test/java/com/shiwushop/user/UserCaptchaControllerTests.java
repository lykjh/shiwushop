package com.shiwushop.user;

import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.captcha.CaptchaRateLimiter;
import com.shiwushop.account.controller.user.CaptchaController;
import com.shiwushop.account.dto.user.CaptchaVerifyDTO;
import com.shiwushop.common.captcha.CaptchaService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserCaptchaControllerTests {
    @Test
    void generationKeepsTheExistingUserRoute() throws Exception {
        CaptchaService captcha = mock(CaptchaService.class);
        CaptchaRateLimiter limiter = mock(CaptchaRateLimiter.class);

        MockMvcBuilders.standaloneSetup(new CaptchaController(captcha, limiter)).build()
                .perform(get("/users/generate"))
                .andExpect(status().isOk());
    }

    @Test
    void failedVerificationRecordsFailureAndSuccessClearsIt() {
        CaptchaService captcha = mock(CaptchaService.class);
        CaptchaRateLimiter limiter = mock(CaptchaRateLimiter.class);
        CaptchaController controller = new CaptchaController(captcha, limiter);
        MockHttpServletRequest request = new MockHttpServletRequest();
        String sessionId = request.getSession(true).getId();
        CaptchaVerifyDTO dto = new CaptchaVerifyDTO("captcha-id", mock(ImageCaptchaTrack.class));
        when(captcha.verifyAndIssueProof(dto.id(), dto.track(), sessionId))
                .thenThrow(new BusinessException(400, "滑块验证失败"))
                .thenReturn("proof");

        assertThrows(BusinessException.class, () -> controller.valid(dto, request));
        verify(limiter).recordValidFailure(sessionId);
        controller.valid(dto, request);
        verify(limiter).clearValidFailures(sessionId);
    }
}
