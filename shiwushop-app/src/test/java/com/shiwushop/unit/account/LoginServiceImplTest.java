package com.shiwushop.unit.account;

import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.user.auth.*;
import com.shiwushop.user.dto.user.LoginDTO;
import com.shiwushop.user.entity.status.*;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.mapper.user.UserAccountMapper;
import com.shiwushop.user.service.impl.LoginServiceImpl;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.captcha.CaptchaService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoginServiceImplTest {
    final UserAccountMapper accounts = mock(UserAccountMapper.class);
    final AccountAccess access = mock(AccountAccess.class);
    final LoginRateLimiter ingress = mock(LoginRateLimiter.class);
    final AuthRiskLimiter risk = mock(AuthRiskLimiter.class);
    final CaptchaService captcha = mock(CaptchaService.class);
    final LoginServiceImpl service = new LoginServiceImpl(accounts, access, ingress, risk, captcha);
    final MockHttpServletRequest request = new MockHttpServletRequest();
    UserAccount account;
    LoginDTO dto;
    @BeforeEach void prepare() {
        account = new UserAccount(); account.setUserId(42L); account.setUsername("buyer_zhang");
        account.setRole(AccountRole.USER); account.setStatus(AccountStatus.ACTIVE); account.setAuthVersion(3);
        account.setPasswordHash(new BCryptPasswordEncoder().encode("Shop123!"));
        when(accounts.selectOne(any())).thenReturn(account); when(access.requireActive(42L)).thenReturn(account);
        request.setRemoteAddr("198.51.100.15"); dto = new LoginDTO("buyer_zhang", "Shop123!", null);
    }
    @Test void successRotatesSessionAndSetsOnlyVerifiedIdentity() {
        String before = request.getSession(true).getId();
        assertEquals(42L, service.login(dto, request).getId());
        assertNotEquals(before, request.getSession().getId());
        assertEquals(42L, request.getSession().getAttribute("userId"));
        assertEquals(3, request.getSession().getAttribute("authVersion"));
        verify(risk).recordLoginSuccess("buyer_zhang", request);
        verify(risk, never()).recordLoginFailure(anyString(), any());
    }
    @ParameterizedTest @ValueSource(strings = {"wrong123", "shop123!", " Shop123!", "Shop123! "})
    void incorrectPasswordNeverSetsIdentity(String password) {
        dto.setPassword(password); assertFailure();
    }
    @Test void absentAccountHasSameFailure() { when(accounts.selectOne(any())).thenReturn(null); assertFailure(); }
    @Test void disabledAccountHasSameFailure() { account.setStatus(AccountStatus.DISABLED); assertFailure(); }
    @Test void oversizedPasswordHasSameFailure() { dto.setPassword("a".repeat(73)); assertFailure(); }
    @Test void highRiskRequiresProofBeforeReadingAccount() {
        when(risk.loginRequiresCaptcha(anyString(), any())).thenReturn(true);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.login(dto, request)).getCode());
        verifyNoInteractions(accounts); assertNull(request.getSession().getAttribute("userId"));
    }
    @Test void submittedProofIsValidatedEvenAtLowRisk() {
        dto.setCaptchaProof("invalid");
        doThrow(new BusinessException(400, "滑块验证已失效")).when(captcha).consumeLoginProof(anyString(), anyString());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.login(dto, request)).getCode());
        verifyNoInteractions(accounts); assertNull(request.getSession().getAttribute("userId"));
    }
    @Test void redisRiskOutageCannotGrantIdentity() {
        when(risk.loginRequiresCaptcha(anyString(), any())).thenThrow(new BusinessException(503, "风险服务不可用"));
        assertEquals(503, assertThrows(BusinessException.class, () -> service.login(dto, request)).getCode());
        verifyNoInteractions(accounts); assertNull(request.getSession().getAttribute("userId"));
    }
    private void assertFailure() {
        var error = assertThrows(BusinessException.class, () -> service.login(dto, request));
        assertEquals(401, error.getCode()); assertEquals("账号或密码错误", error.getMessage());
        assertNull(request.getSession().getAttribute("userId"));
        verify(risk).recordLoginFailure("buyer_zhang", request);
        verify(risk, never()).recordLoginSuccess(anyString(), any());
    }
}
