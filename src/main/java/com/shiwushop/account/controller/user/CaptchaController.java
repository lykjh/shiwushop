package com.shiwushop.account.controller.user;

import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.response.ApiResponse;
import com.shiwushop.account.dto.user.CaptchaVerifyDTO;
import com.shiwushop.account.vo.user.CaptchaProofVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.Result;
import com.shiwushop.common.captcha.CaptchaRateLimiter;
import com.shiwushop.common.captcha.CaptchaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 滑块验证与账号操作使用同一路径前缀，但分别维护资源额度和凭证。 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class CaptchaController {
    private final CaptchaService captchaService;
    private final CaptchaRateLimiter captchaRateLimiter;

    @GetMapping("/generate")
    public Result<ApiResponse<ImageCaptchaVO>> generate(HttpServletRequest request) {
        captchaRateLimiter.checkGenerate(request);
        return Result.success(captchaService.generateCaptcha());
    }

    @PostMapping("/valid")
    public Result<CaptchaProofVO> valid(@Valid @RequestBody CaptchaVerifyDTO body,
                                        HttpServletRequest request) {
        captchaRateLimiter.checkValid(request);
        HttpSession session = request.getSession(true);
        String proof;
        try {
            proof = captchaService.verifyAndIssueProof(body.id(), body.track(), session.getId());
        } catch (BusinessException e) {
            if (e.getCode() == 400) captchaRateLimiter.recordValidFailure(session.getId());
            throw e;
        }
        captchaRateLimiter.clearValidFailures(session.getId());
        return Result.success(new CaptchaProofVO(proof));
    }
}
