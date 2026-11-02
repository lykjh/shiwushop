package com.shiwushop.user.service.impl;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.shiwushop.user.service.SmsSender;
import com.shiwushop.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class AliyunSmsSender implements SmsSender {
    private static final Logger log = LoggerFactory.getLogger(AliyunSmsSender.class);
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String signName;
    private final String templateCode;

    public AliyunSmsSender(@Value("${shop.sms.access-key-id:}") String accessKeyId,
                           @Value("${shop.sms.access-key-secret:}") String accessKeySecret,
                           @Value("${shop.sms.sign-name:}") String signName,
                           @Value("${shop.sms.template-code:}") String templateCode) {
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.signName = signName;
        this.templateCode = templateCode;
    }

    /** 缺少任一渠道配置时，所有发码请求统一返回服务不可用。 */
    @Override
    public boolean configured() {
        return !accessKeyId.isBlank() && !accessKeySecret.isBlank()
                && !signName.isBlank() && !templateCode.isBlank();
    }

    /** 传入本地生成的验证码，由 Redis 负责一次性校验；渠道未接受时不激活挑战。 */
    @Override
    public void send(String phone, String code) {
        if (!configured())
            throw new BusinessException(503, "短信渠道未配置");
        Config config = new Config().setAccessKeyId(accessKeyId)
                .setAccessKeySecret(accessKeySecret).setEndpoint("dypnsapi.aliyuncs.com")
                .setConnectTimeout(3000).setReadTimeout(5000);
        SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest().setPhoneNumber(phone)
                .setSignName(signName).setTemplateCode(templateCode)
                .setReturnVerifyCode(false)
                .setTemplateParam("{\"code\":\"" + code + "\",\"min\":\"5\"}");
        SendSmsVerifyCodeResponse response;
        // SDK 声明 throws Exception；捕获范围只覆盖客户端构造和网络调用。
        try {
            response = new Client(config).sendSmsVerifyCode(request);
        } catch (TeaException e) {
            log.warn("sms aliyun request failed code={} status={}", e.getCode(), e.getStatusCode());
            throw new BusinessException(503, "短信渠道暂时不可用");
        } catch (Exception e) {
            log.warn("sms aliyun call failed error={}", e.getClass().getSimpleName());
            throw new BusinessException(503, "短信渠道暂时不可用");
        }
        if (response.getBody() == null) {
            log.warn("sms aliyun response body missing");
            throw new BusinessException(503, "短信渠道暂时不可用");
        }
        if (!Boolean.TRUE.equals(response.getBody().getSuccess())
                || !"OK".equals(response.getBody().getCode())) {
            log.warn("sms aliyun rejected code={} requestId={}",
                    response.getBody().getCode(), response.getBody().getRequestId());
            if ("biz.FREQUENCY".equals(response.getBody().getCode()))
                throw new BusinessException(429, "验证码发送过于频繁，请稍后再试");
            throw new BusinessException(503, "短信渠道暂时不可用");
        }

    }
}
