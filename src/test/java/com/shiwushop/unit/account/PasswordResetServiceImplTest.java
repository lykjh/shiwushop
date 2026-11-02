package com.shiwushop.unit.account;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.RateLimiter;
import com.shiwushop.user.auth.*;
import com.shiwushop.user.entity.status.AccountStatus;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.mapper.user.*;
import com.shiwushop.user.service.impl.PasswordResetServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 不启动 Spring；逐项验证凭证、到期边界、账号状态、版本和事务写入条件。 */
class PasswordResetServiceImplTest {
    final PhoneLookup phones = mock(PhoneLookup.class);
    final PhoneBindingMapper bindings = mock(PhoneBindingMapper.class);
    final UserAccountMapper accounts = mock(UserAccountMapper.class);
    final SmsChallengeStore challenges = mock(SmsChallengeStore.class);
    final LoginRateLimiter loginLimiter = mock(LoginRateLimiter.class);
    final RateLimiter limiter = mock(RateLimiter.class);
    final AccountAuthCache cache = mock(AccountAuthCache.class);
    final Clock clock = mock(Clock.class);
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    PasswordResetServiceImpl service;
    MockHttpServletRequest request;
    UserAccount account;
    final Instant issuedAt = Instant.parse("2026-11-02T08:00:00Z");

    @BeforeEach void prepare() {
        // 发送边界在真实 HTTP 集成测试中覆盖，此类只测试核验与重设。
        service = new PasswordResetServiceImpl(phones, bindings, accounts, challenges, null,
                loginLimiter, limiter, cache, clock);
        request = new MockHttpServletRequest();
        account = new UserAccount(); account.setUserId(71L); account.setStatus(AccountStatus.ACTIVE);
        account.setAuthVersion(2); account.setPasswordHash(encoder.encode("Shop123!"));
        when(clock.instant()).thenReturn(issuedAt);
        when(phones.digest("13812345678")).thenReturn("phone-hmac");
        when(phones.codeMac("password-reset", "phone-hmac", "123456")).thenReturn("code-hmac");
        when(challenges.consume("phone-hmac", "password-reset", "code-hmac")).thenReturn(true);
        when(bindings.findUserId("phone-hmac")).thenReturn(71L);
        when(accounts.selectById(71L)).thenReturn(account);
        when(accounts.lockById(71L)).thenReturn(account);
        when(accounts.changePassword(eq(71L), eq(2), anyString())).thenReturn(1);
        when(limiter.digest(anyString())).thenAnswer(call -> "digest:" + call.getArgument(0));
    }

    @Test void validProofWritesBcryptAndRaisesAuthVersionWithoutLogin() {
        var proof = service.verify("13812345678", "123456", request);
        assertEquals(300, proof.expiresInSeconds()); assertTrue(proof.resetTicket().matches("[A-Za-z0-9_-]{43}"));
        assertNull(request.getSession().getAttribute("userId"));
        service.reset(proof.resetTicket(), "Shop456!", request);
        var hash = ArgumentCaptor.forClass(String.class);
        verify(accounts).changePassword(eq(71L), eq(2), hash.capture());
        assertTrue(encoder.matches("Shop456!", hash.getValue()));
        verify(cache).blockUntilTransactionEnds(71L, new AccountAuthCache.State(AccountStatus.ACTIVE, 3));
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.reset(proof.resetTicket(), "Shop789!", request)).getCode());
    }

    @ParameterizedTest @ValueSource(longs = {299, 300, 301})
    void expiryUsesStrictFiveMinuteDeadline(long seconds) {
        var proof = service.verify("13812345678", "123456", request);
        when(clock.instant()).thenReturn(issuedAt.plusSeconds(seconds));
        if (seconds < 300) service.reset(proof.resetTicket(), "Shop456!", request);
        else {
            assertEquals(401, assertThrows(BusinessException.class,
                    () -> service.reset(proof.resetTicket(), "Shop456!", request)).getCode());
            verify(accounts, never()).changePassword(anyLong(), anyInt(), anyString());
        }
    }

    @Test void expiredWhileWaitingForAccountLockIsRejected() {
        var proof = service.verify("13812345678", "123456", request);
        when(accounts.lockById(71L)).thenAnswer(call -> {when(clock.instant()).thenReturn(issuedAt.plusSeconds(300)); return account;});
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.reset(proof.resetTicket(), "Shop456!", request)).getCode());
    }

    @Test void wrongCodeNeverCreatesGrant() {
        when(challenges.consume(anyString(), anyString(), anyString())).thenReturn(false);
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.verify("13812345678", "123456", request)).getCode());
        assertNull(request.getSession(false)); verifyNoInteractions(accounts);
    }

    @Test void unboundPhoneDoesNotCreateAccountOrGrant() {
        when(bindings.findUserId("phone-hmac")).thenReturn(null);
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.verify("13812345678", "123456", request)).getCode());
        assertNull(request.getSession(false)); verifyNoInteractions(accounts);
    }

    @Test void disabledAccountCannotObtainGrant() {
        account.setStatus(AccountStatus.DISABLED);
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.verify("13812345678", "123456", request)).getCode());
        assertNull(request.getSession(false));
    }

    @Test void ticketIsBoundToSessionAndLatestVerification() {
        var old = service.verify("13812345678", "123456", request);
        var latest = service.verify("13812345678", "123456", request);
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.reset(latest.resetTicket(), "Shop456!", new MockHttpServletRequest())).getCode());
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.reset(old.resetTicket(), "Shop456!", request)).getCode());
        service.reset(latest.resetTicket(), "Shop456!", request);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "disabled", "version", "phone"})
    void accountChangesAfterVerificationInvalidateGrant(String change) {
        var proof = service.verify("13812345678", "123456", request);
        switch (change) {
            case "missing" -> when(accounts.lockById(71L)).thenReturn(null);
            case "disabled" -> account.setStatus(AccountStatus.DISABLED);
            case "version" -> account.setAuthVersion(3);
            case "phone" -> when(bindings.findUserId("phone-hmac")).thenReturn(72L);
        }
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.reset(proof.resetTicket(), "Shop456!", request)).getCode());
        verify(accounts, never()).changePassword(anyLong(), anyInt(), anyString());
    }

    @Test void identicalPasswordIsRejectedAndValidProofCanBeCorrected() {
        var proof = service.verify("13812345678", "123456", request);
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.reset(proof.resetTicket(), "Shop123!", request)).getCode());
        service.reset(proof.resetTicket(), "Shop456!", request);
    }

    @Test void authCacheFailureStopsPasswordUpdate() {
        var proof = service.verify("13812345678", "123456", request);
        doThrow(new BusinessException(503, "账号校验暂不可用")).when(cache).blockUntilTransactionEnds(anyLong(), any());
        assertEquals(503, assertThrows(BusinessException.class,
                () -> service.reset(proof.resetTicket(), "Shop456!", request)).getCode());
        verify(accounts, never()).changePassword(anyLong(), anyInt(), anyString());
    }

    @Test void databaseFailurePropagatesAndSpentProofIsNotRestored() {
        var proof = service.verify("13812345678", "123456", request);
        when(accounts.changePassword(eq(71L), eq(2), anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThrows(DataAccessResourceFailureException.class, () -> service.reset(proof.resetTicket(), "Shop456!", request));
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.reset(proof.resetTicket(), "Shop456!", request)).getCode());
    }

    @Test void conditionalWriteConflictIsReported() {
        var proof = service.verify("13812345678", "123456", request);
        when(accounts.changePassword(eq(71L), eq(2), anyString())).thenReturn(0);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.reset(proof.resetTicket(), "Shop456!", request)).getCode());
    }
}
