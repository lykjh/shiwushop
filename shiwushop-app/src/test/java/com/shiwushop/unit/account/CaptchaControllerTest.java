package com.shiwushop.unit.account;

import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.BrowserFlow;
import com.shiwushop.common.captcha.CaptchaRateLimiter;
import com.shiwushop.common.captcha.controller.CaptchaController;
import com.shiwushop.common.captcha.dto.CaptchaVerifyDTO;
import com.shiwushop.common.captcha.CaptchaService;
import com.shiwushop.support.MockBrowserFlow;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptchaControllerTest {
    @Test
    void failedVerificationRecordsFailureAndSuccessClearsIt() {
        CaptchaService captcha = mock(CaptchaService.class);
        CaptchaRateLimiter limiter = mock(CaptchaRateLimiter.class);
        CaptchaController controller = new CaptchaController(captcha, limiter, mock(BrowserFlow.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        String browserId = "captcha-browser";
        MockBrowserFlow.bind(request, browserId);
        CaptchaVerifyDTO dto = new CaptchaVerifyDTO("captcha-id", mock(ImageCaptchaTrack.class));
        when(captcha.verifyAndIssueProof(dto.id(), dto.track(), browserId))
                .thenThrow(new BusinessException(400, "滑块验证失败"))
                .thenReturn("proof");

        assertThrows(BusinessException.class, () -> controller.valid(dto, request));
        verify(limiter).recordValidFailure(browserId);
        controller.valid(dto, request);
        verify(limiter).clearValidFailures(browserId);
    }
}
