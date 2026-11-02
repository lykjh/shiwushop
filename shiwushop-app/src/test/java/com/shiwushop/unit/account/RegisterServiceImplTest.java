package com.shiwushop.unit.account;

import com.shiwushop.user.auth.*;
import com.shiwushop.user.dto.user.RegisterDTO;
import com.shiwushop.user.entity.status.*;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.mapper.user.*;
import com.shiwushop.user.service.impl.RegisterServiceImpl;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.captcha.CaptchaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 注册服务的决策测试；事务真实回滚另由 RegisterHttpTest 验证。 */
class RegisterServiceImplTest {
    private final UserProfileMapper profiles = mock(UserProfileMapper.class);
    private final UserAccountMapper accounts = mock(UserAccountMapper.class);
    private final RegisterRateLimiter ingress = mock(RegisterRateLimiter.class);
    private final AuthRiskLimiter risk = mock(AuthRiskLimiter.class);
    private final CaptchaService captcha = mock(CaptchaService.class);
    private final RegisterServiceImpl service = new RegisterServiceImpl(profiles, accounts, ingress, risk, captcha);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private RegisterDTO dto;
    @BeforeEach void prepare() {
        request.setRemoteAddr("198.51.100.10");
        dto = new RegisterDTO("buyer_zhang", "Shop123!", "proof");
        doAnswer(call -> { ((com.shiwushop.user.entity.user.UserProfile) call.getArgument(0)).setId(42L); return null; })
                .when(profiles).insertEmpty(any());
    }
    @Test void createsDefaultIdentityAndMatchingHash() {
        service.register(dto, request);
        var captured = ArgumentCaptor.forClass(UserAccount.class);
        verify(accounts).insert(captured.capture());
        var account = captured.getValue();
        assertEquals(42L, account.getUserId()); assertEquals("buyer_zhang", account.getUsername());
        assertEquals(AccountRole.USER, account.getRole()); assertEquals(AccountStatus.ACTIVE, account.getStatus());
        assertTrue(new BCryptPasswordEncoder().matches("Shop123!", account.getPasswordHash()));
    }
    @Test void missingProofRequiresChallengeBeforeWriting() {
        dto.setCaptchaProof(null);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.register(dto, request)).getCode());
        verifyNoInteractions(profiles, accounts, captcha);
    }
    @Test void invalidProofRecordsRiskWithoutCreatingAccount() {
        doThrow(new BusinessException(400, "凭证无效")).when(captcha).consumeLoginProof(anyString(), anyString());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.register(dto, request)).getCode());
        verify(risk).recordRegisterFailure(request); verifyNoInteractions(profiles, accounts);
    }
    @Test void captchaOutageIsNotCountedAsUserFailure() {
        doThrow(new BusinessException(503, "验证服务暂时不可用")).when(captcha).consumeLoginProof(anyString(), anyString());
        assertEquals(503, assertThrows(BusinessException.class, () -> service.register(dto, request)).getCode());
        verify(risk, never()).recordRegisterFailure(any()); verifyNoInteractions(profiles, accounts);
    }
    @Test void ingressRejectionDoesNotReachCaptchaOrDatabase() {
        doThrow(new BusinessException(429, "频繁")).when(ingress).check(anyString());
        assertEquals(429, assertThrows(BusinessException.class, () -> service.register(dto, request)).getCode());
        verifyNoInteractions(captcha, profiles, accounts, risk);
    }
    @Test void duplicateUsernameBecomesConflictWithoutCountingCaptchaFailure() {
        when(accounts.insert(any(UserAccount.class))).thenThrow(new DuplicateKeyException("duplicate"));
        var error = assertThrows(BusinessException.class, () -> service.register(dto, request));
        assertEquals(409, error.getCode()); assertEquals("账号已存在", error.getMessage());
        verify(risk, never()).recordRegisterFailure(any());
    }
}
