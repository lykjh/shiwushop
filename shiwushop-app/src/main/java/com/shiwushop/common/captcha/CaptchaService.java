package com.shiwushop.common.captcha;

import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.response.ApiResponse;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;

public interface CaptchaService {
    /** 生成滑块图像，供客户端展示及后续轨迹验证。 */
    ApiResponse<ImageCaptchaVO> generateCaptcha();

    /** 验证滑块轨迹并签发绑定 Session 的一次性凭证。 */
    String verifyAndIssueProof(String id, ImageCaptchaTrack track, String sessionId);

    /** 在登录或注册时消费一次性凭证。 */
    void consumeLoginProof(String proof, String sessionId);
}
