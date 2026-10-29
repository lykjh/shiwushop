package com.shiwushop.common.captcha;

import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.response.ApiResponse;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;

public interface CaptchaService {
    // 生成滑块验证码
    ApiResponse<ImageCaptchaVO> generateCaptcha();

    String verifyAndIssueProof(String id, ImageCaptchaTrack track, String sessionId);

    void consumeLoginProof(String proof, String sessionId);
}
