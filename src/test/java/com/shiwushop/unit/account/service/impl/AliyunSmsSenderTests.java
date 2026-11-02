package com.shiwushop.unit.account.service.impl;
import com.shiwushop.user.service.impl.*;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.shiwushop.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AliyunSmsSenderTests {
    private final AliyunSmsSender sender = new AliyunSmsSender("access-id", "access-secret", "恒创联众", "100001");

    @Test
    void sendsLocallyGeneratedCodeThroughNumberVerificationService() throws Exception {
        var response = response(true, "OK");
        try (MockedConstruction<Client> clients = mockConstruction(Client.class,
                (client, context) -> when(client.sendSmsVerifyCode(any())).thenReturn(response))) {
            sender.send("13800000001", "123456");

            var request = ArgumentCaptor.forClass(SendSmsVerifyCodeRequest.class);
            verify(clients.constructed().getFirst()).sendSmsVerifyCode(request.capture());
            assertThat(request.getValue().getPhoneNumber()).isEqualTo("13800000001");
            assertThat(request.getValue().getSignName()).isEqualTo("恒创联众");
            assertThat(request.getValue().getTemplateCode()).isEqualTo("100001");
            assertThat(request.getValue().getReturnVerifyCode()).isFalse();
            assertThat(request.getValue().getTemplateParam())
                    .isEqualTo("{\"code\":\"123456\",\"min\":\"5\"}");
        }
    }

    @Test
    void frequencyRejectionIsNotTreatedAsSuccessfulDelivery() {
        try (MockedConstruction<Client> ignored = mockConstruction(Client.class,
                (client, context) -> when(client.sendSmsVerifyCode(any())).thenReturn(response(false, "biz.FREQUENCY")))) {
            assertThatThrownBy(() -> sender.send("13800000001", "123456"))
                    .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(429));
        }
    }

    @Test
    void httpSuccessWithBusinessFailureDoesNotActivateCode() {
        try (MockedConstruction<Client> ignored = mockConstruction(Client.class,
                (client, context) -> when(client.sendSmsVerifyCode(any())).thenReturn(response(false, "OK")))) {
            assertThatThrownBy(() -> sender.send("13800000001", "123456"))
                    .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(503));
        }
    }

    private SendSmsVerifyCodeResponse response(boolean success, String code) {
        return new SendSmsVerifyCodeResponse()
                .setBody(new SendSmsVerifyCodeResponseBody().setSuccess(success).setCode(code));
    }
}
