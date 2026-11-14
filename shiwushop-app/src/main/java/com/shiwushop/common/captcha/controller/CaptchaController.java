package com.shiwushop.common.captcha.controller;

import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.response.ApiResponse;
import com.shiwushop.common.captcha.dto.CaptchaVerifyDTO;
import com.shiwushop.common.captcha.vo.CaptchaProofVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.BrowserFlow;
import com.shiwushop.common.captcha.CaptchaRateLimiter;
import com.shiwushop.common.captcha.CaptchaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只处理滑块挑战的生成与验证。 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class CaptchaController {
    /** 提供滑块图像、轨迹验证和一次性凭证消费。 */
    private final CaptchaService captchaService;
    /** 限制滑块生成、校验及失败重试。 */
    private final CaptchaRateLimiter captchaRateLimiter;
    private final BrowserFlow browserFlow;

    /**
     * 先检查图片生成额度，再返回滑块挑战。
     * 生成图片会占用处理资源；限流器负责控制入口负载。
     */
    @GetMapping("/generate")
    public Result<ApiResponse<ImageCaptchaVO>> generate(HttpServletRequest request, HttpServletResponse response) {
        browserFlow.getOrCreate(request, response);
        captchaRateLimiter.checkGenerate(request);
        return Result.success(captchaService.generateCaptcha());
    }

    /**
     * 验证滑动轨迹并将一次性凭证绑定到当前浏览器。
     * 业务校验失败时记录失败次数，成功后清除该浏览器的失败计数。
     */
    @PostMapping("/valid")
    public Result<CaptchaProofVO> valid(@Valid @RequestBody CaptchaVerifyDTO body,
                                        HttpServletRequest request) {
        captchaRateLimiter.checkValid(request);
        String browserId = BrowserFlow.requireId(request);
        String proof;
        try {
            proof = captchaService.verifyAndIssueProof(body.id(), body.track(), browserId);
        } catch (BusinessException e) {
            if (e.getCode() == 400)
                captchaRateLimiter.recordValidFailure(browserId);
            throw e;
        }
        captchaRateLimiter.clearValidFailures(browserId);
        return Result.success(new CaptchaProofVO(proof));
    }
}
