package com.shiwushop.account.service.impl;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import com.shiwushop.account.service.SmsSender;
import com.shiwushop.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AliyunSmsSender implements SmsSender {
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

    /** 仅在渠道明确接受短信后返回；缺配置或渠道异常均失败关闭。 */
    @Override
    public void send(String phone, String code) {
        if (!configured())
            throw new BusinessException(503, "短信渠道未配置");
        try {
            Config config = new Config().setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret).setEndpoint("dysmsapi.aliyuncs.com")
                    .setConnectTimeout(3000).setReadTimeout(5000);
            SendSmsRequest request = new SendSmsRequest().setPhoneNumbers(phone)
                    .setSignName(signName).setTemplateCode(templateCode)
                    .setTemplateParam("{\"code\":\"" + code + "\"}");
            SendSmsResponse response = new Client(config).sendSms(request);
            if (response.getBody() == null || !"OK".equals(response.getBody().getCode()))
                throw new BusinessException(503, "短信渠道暂时不可用");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(503, "短信渠道暂时不可用");
        }
    }
}
