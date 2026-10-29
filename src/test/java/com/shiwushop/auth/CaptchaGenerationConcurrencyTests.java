package com.shiwushop.auth;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.common.response.ApiResponse;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.common.captcha.CaptchaServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaptchaGenerationConcurrencyTests {
    @Test
    @SuppressWarnings("unchecked")
    void generationHasBoundedWorkAndWait() throws Exception {
        ImageCaptchaApplication application = mock(ImageCaptchaApplication.class);
        ApiResponse<ImageCaptchaVO> response = mock(ApiResponse.class);
        when(response.isSuccess()).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        when(application.generateCaptcha(CaptchaTypeConstant.SLIDER)).thenAnswer(call -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                assertTrue(release.await(3, TimeUnit.SECONDS));
                return response;
            } finally {
                active.decrementAndGet();
            }
        });
        CaptchaServiceImpl service = new CaptchaServiceImpl(application, mock(CaptchaProofStore.class), 2, 100);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(service::generateCaptcha);
            var second = workers.submit(service::generateCaptcha);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                BusinessException error = assertThrows(BusinessException.class, service::generateCaptcha);
                assertEquals(429, error.getCode());
            } finally {
                release.countDown();
            }
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
        }
        assertEquals(2, peak.get());
    }
}
