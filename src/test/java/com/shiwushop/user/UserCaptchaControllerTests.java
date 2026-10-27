package com.shiwushop.user;

import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountAccess;
import com.shiwushop.common.auth.AuthRiskLimiter;
import com.shiwushop.common.auth.CaptchaRateLimiter;
import com.shiwushop.common.auth.LoginRateLimiter;
import com.shiwushop.common.auth.RegisterRateLimiter;
import com.shiwushop.controller.user.UserController;
import com.shiwushop.dto.user.CaptchaVerifyDTO;
import com.shiwushop.service.CaptchaService;
import com.shiwushop.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserCaptchaControllerTests {
    @Test
    void failedVerificationRecordsFailureAndSuccessClearsIt() {
        CaptchaService captcha = mock(CaptchaService.class);
        CaptchaRateLimiter limiter = mock(CaptchaRateLimiter.class);
        UserController controller = new UserController(mock(UserService.class),
                mock(AuthRiskLimiter.class), captcha, limiter);
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
