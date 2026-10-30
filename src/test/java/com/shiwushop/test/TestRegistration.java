package com.shiwushop.test;

import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.account.service.RegisterService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.UUID;

public final class TestRegistration {
    private TestRegistration() {}

    public static void register(RegisterService users, CaptchaProofStore proofs, RegisterDTO dto) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // 测试数据准备使用独立来源，避免无关用例共享注册入口额度。
        request.setRemoteAddr("test-setup-" + UUID.randomUUID());
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        dto.setCaptchaProof(proofs.issue("test-setup-" + UUID.randomUUID(), session.getId()));
        users.register(dto, request);
    }
}
