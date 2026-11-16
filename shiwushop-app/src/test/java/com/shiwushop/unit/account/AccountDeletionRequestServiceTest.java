package com.shiwushop.unit.account;

import com.shiwushop.common.BusinessException;
import com.shiwushop.user.entity.status.AccountRole;
import com.shiwushop.user.entity.status.AccountStatus;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.entity.user.AccountDeletionRequest;
import com.shiwushop.user.mapper.user.AccountDeletionRequestMapper;
import com.shiwushop.user.mapper.user.UserAccountMapper;
import com.shiwushop.user.service.impl.AccountDeletionRequestServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountDeletionRequestServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final UserAccountMapper accounts = mock(UserAccountMapper.class);
    private final AccountDeletionRequestMapper requests = mock(AccountDeletionRequestMapper.class);
    private final AccountDeletionRequestServiceImpl service = new AccountDeletionRequestServiceImpl(accounts, requests, CLOCK);

    @Test
    void repeatedPendingRequestDoesNotInsertAgain() {
        active(2L);
        var pending = pending(7L);
        when(requests.pending(2L)).thenReturn(pending);
        assertSame(pending, service.request(2L, 0));
        verify(requests, never()).request(any(), any(), any());
    }

    @Test
    void requestOpensSevenDayWindow() {
        active(2L);
        var pending = pending(8L);
        when(requests.pending(2L)).thenReturn(null, pending);
        assertSame(pending, service.request(2L, 0));
        var start = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        var deadline = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(requests).request(eq(2L), start.capture(), deadline.capture());
        assertEquals(start.getValue().plusDays(7), deadline.getValue());
    }

    @Test
    void repeatedWithdrawDoesNotWriteAgain() {
        active(2L);
        var withdrawn = new AccountDeletionRequest(7L, 2L, "WITHDRAWN",
                LocalDateTime.now(CLOCK).minusDays(1), LocalDateTime.now(CLOCK).plusDays(6), LocalDateTime.now(CLOCK));
        when(requests.latest(2L)).thenReturn(withdrawn);
        assertSame(withdrawn, service.withdraw(2L, 0));
        verify(requests, never()).withdraw(any(), any());
    }

    @Test
    void deadlineIsExclusiveAndExpiredRequestRemainsPending() {
        active(2L);
        LocalDateTime deadline = LocalDateTime.now(CLOCK);
        var pending = new AccountDeletionRequest(7L, 2L, "PENDING",
                deadline.minusDays(7), deadline, null);
        when(requests.pending(2L)).thenReturn(pending);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.withdraw(2L, 0)).getCode());
        verify(requests, never()).withdraw(any(), any());

        var justBefore = new AccountDeletionRequestServiceImpl(accounts, requests,
                Clock.offset(CLOCK, java.time.Duration.ofNanos(-1)));
        when(requests.latest(2L)).thenReturn(new AccountDeletionRequest(7L, 2L,
                "WITHDRAWN", pending.requestedAt(), pending.deadlineAt(), deadline.minusNanos(1)));
        when(requests.withdraw(eq(7L), any())).thenReturn(1);
        assertEquals("WITHDRAWN", justBefore.withdraw(2L, 0).status());
    }

    @Test
    void staleSessionVersionRejectsRequestBeforeInsert() {
        active(2L);
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.request(2L, 1)).getCode());
        verifyNoInteractions(requests);
    }

    @Test
    void tradeGateFailsClosedOutsideTransaction() {
        assertThrows(IllegalStateException.class, () -> service.requireCanStartTrade(2L));
        verifyNoInteractions(accounts, requests);
    }

    @Test
    void tradeGateRejectsPendingWithinTransaction() {
        active(2L);
        when(requests.pending(2L)).thenReturn(pending(7L));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertEquals(409, assertThrows(BusinessException.class,
                    () -> service.requireCanStartTrade(2L)).getCode());
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private void active(Long id) {
        var account = new UserAccount();
        account.setUserId(id);
        account.setRole(AccountRole.USER);
        account.setStatus(AccountStatus.ACTIVE);
        account.setAuthVersion(0);
        when(accounts.lockById(id)).thenReturn(account);
    }

    private static AccountDeletionRequest pending(Long id) {
        return new AccountDeletionRequest(id, 2L, "PENDING",
                LocalDateTime.now(CLOCK), LocalDateTime.now(CLOCK).plusDays(7), null);
    }
}
