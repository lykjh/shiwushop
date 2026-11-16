package com.shiwushop.user.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.user.auth.PhoneLookup;
import com.shiwushop.user.auth.SmsChallengeStore;
import com.shiwushop.user.entity.status.AccountStatus;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.mapper.user.PhoneBindingMapper;
import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.user.service.AccountProofService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountProofServiceTest {
    private final AccountAccess access = mock(AccountAccess.class);
    private final PhoneBindingMapper bindings = mock(PhoneBindingMapper.class);
    private final PrivacyCipher privacy = mock(PrivacyCipher.class);
    private final PhoneLookup phones = mock(PhoneLookup.class);
    private final SmsChallengeStore challenges = mock(SmsChallengeStore.class);
    private final SmsCodeDelivery delivery = mock(SmsCodeDelivery.class);
    private final AccountProofService proof = new AccountProofServiceImpl(access, bindings, privacy, phones, challenges, delivery);

    @Test
    void requiresExactlyOneProof() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> proof.verify(2L, "session-revoke", null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> proof.verify(2L, "session-revoke", "password", "123456")).getCode());
        verifyNoInteractions(access, challenges);
    }

    @Test
    void passwordMustMatchCurrentAccount() {
        var account = new UserAccount();
        account.setStatus(AccountStatus.ACTIVE);
        account.setPasswordHash(new BCryptPasswordEncoder().encode("correct"));
        when(access.requireActive(2L)).thenReturn(account);
        assertEquals(401, assertThrows(BusinessException.class,
                () -> proof.verify(2L, "session-revoke", "wrong", null)).getCode());
        assertEquals(401, assertThrows(BusinessException.class,
                () -> proof.verify(2L, "session-revoke", "a".repeat(73), null)).getCode());
        assertDoesNotThrow(() -> proof.verify(2L, "session-revoke", "correct", null));
    }

    @Test
    void smsIsBoundToPurposeAndConsumedOnce() {
        var bound = new PhoneBindingMapper.PhonePrivate();
        bound.setCiphertext(new byte[] {1});
        bound.setKeyVersion(1);
        when(bindings.findPhone(2L)).thenReturn(bound);
        when(privacy.decrypt(2L, "login-phone", 1, bound.getCiphertext())).thenReturn("13900000000");
        when(phones.digest("13900000000")).thenReturn("digest");
        when(phones.codeMac("session-revoke", "digest", "123456")).thenReturn("mac");
        when(challenges.consume("digest", "session-revoke", "mac")).thenReturn(true, false);
        assertDoesNotThrow(() -> proof.verify(2L, "session-revoke", null, "123456"));
        assertEquals(401, assertThrows(BusinessException.class,
                () -> proof.verify(2L, "session-revoke", null, "123456")).getCode());
        when(phones.codeMac("account-deletion", "digest", "123456")).thenReturn("deletion-mac");
        assertEquals(401, assertThrows(BusinessException.class,
                () -> proof.verify(2L, "account-deletion", null, "123456")).getCode());
        verify(challenges).consume("digest", "account-deletion", "deletion-mac");
    }
}
