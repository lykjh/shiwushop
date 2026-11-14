package com.shiwushop.support;

import com.shiwushop.user.dto.user.RegisterDTO;
import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.user.service.RegisterService;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

public final class TestRegistration {
    private TestRegistration() {
    }

    public static void register(RegisterService users, CaptchaProofStore proofs, RegisterDTO dto) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // 测试数据准备使用独立来源，避免无关用例共享注册入口额度。
        request.setRemoteAddr("test-setup-" + UUID.randomUUID());
        String browser = UUID.randomUUID().toString();
        MockBrowserFlow.bind(request, browser);
        dto.setCaptchaProof(proofs.issue("test-setup-" + UUID.randomUUID(), browser));
        users.register(dto, request);
    }
}
