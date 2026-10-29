package com.shiwushop.common.captcha;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.common.response.ApiResponse;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.captcha.CaptchaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class CaptchaServiceImpl implements CaptchaService {
    private final ImageCaptchaApplication captchaApplication;
    private final CaptchaProofStore proofStore;
    private final Semaphore generationSlots;
    private final long generationWaitMs;

    public CaptchaServiceImpl(ImageCaptchaApplication captchaApplication, CaptchaProofStore proofStore,
            @Value("${shop.security.captcha.generate-max-concurrent}") int maxConcurrent,
            @Value("${shop.security.captcha.generate-wait-ms}") long generationWaitMs) {
        if (maxConcurrent < 1 || generationWaitMs < 1) {
            throw new IllegalArgumentException("滑块生成并发参数必须大于零");
        }
        this.captchaApplication = captchaApplication;
        this.proofStore = proofStore;
        this.generationSlots = new Semaphore(maxConcurrent);
        this.generationWaitMs = generationWaitMs;
    }

    @Override
    public ApiResponse<ImageCaptchaVO> generateCaptcha() {
        try {
            // 许可覆盖实际图像生成；等待超时释放请求线程并提示客户端稍后重试。
            if (!generationSlots.tryAcquire(generationWaitMs, TimeUnit.MILLISECONDS)) {
                throw new BusinessException(429, "滑块生成繁忙，请稍后再试",
                        Map.of("retryAfterSeconds", 1), null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "暂时无法生成滑块验证码", e);
        }
        try {
            ApiResponse<ImageCaptchaVO> response =
                    captchaApplication.generateCaptcha(CaptchaTypeConstant.SLIDER);
            if (response == null || !response.isSuccess()) {
                throw new BusinessException(503, "暂时无法生成滑块验证码");
            }
            return response;
        } finally {
            generationSlots.release();
        }
    }

    @Override
    public String verifyAndIssueProof(
            String captchaId,
            ImageCaptchaTrack track,
            String sessionId
    ) {
        if (captchaId == null || captchaId.isBlank()
                || captchaId.length() > 128 || track == null) {
            throw new BusinessException(400, "滑块验证参数无效");
        }

        ApiResponse<?> result = captchaApplication.matching(captchaId, track);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(400, "滑块验证失败，请重新获取");
        }

        return proofStore.issue(captchaId, sessionId);
    }

    @Override
    public void consumeLoginProof(String proof, String sessionId) {
        proofStore.consume(proof, sessionId);
    }
}
