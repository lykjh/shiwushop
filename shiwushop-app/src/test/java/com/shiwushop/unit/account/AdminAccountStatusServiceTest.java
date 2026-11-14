package com.shiwushop.unit.account;

import com.shiwushop.common.BusinessException;
import com.shiwushop.user.auth.AccountAuthCache;
import com.shiwushop.user.entity.status.AccountRole;
import com.shiwushop.user.entity.status.AccountStatus;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.mapper.user.AdminAccountActionMapper;
import com.shiwushop.user.mapper.user.UserAccountMapper;
import com.shiwushop.user.service.impl.AdminAccountStatusService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminAccountStatusServiceTest {
    private final UserAccountMapper accounts = mock(UserAccountMapper.class);
    private final AdminAccountActionMapper actions = mock(AdminAccountActionMapper.class);
    private final AccountAuthCache cache = mock(AccountAuthCache.class);
    private final AdminAccountStatusService service = new AdminAccountStatusService(accounts, actions, cache);

    @Test
    void rejectsNonAdminBeforeTargetLookup() {
        when(accounts.lockById(1L)).thenReturn(account(1L, AccountRole.USER, AccountStatus.ACTIVE, 0));
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.change(1L, 2L, AccountStatus.DISABLED, "risk")).getCode());
        verify(accounts, never()).lockById(2L);
    }

    @Test
    void rejectsOwnAndOtherAdmin() {
        when(accounts.lockById(1L)).thenReturn(account(1L, AccountRole.ADMIN, AccountStatus.ACTIVE, 0));
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.change(1L, 1L, AccountStatus.DISABLED, "risk")).getCode());
        when(accounts.lockById(2L)).thenReturn(account(2L, AccountRole.ADMIN, AccountStatus.ACTIVE, 0));
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.change(1L, 2L, AccountStatus.DISABLED, "risk")).getCode());
        verifyNoInteractions(cache, actions);
    }

    @Test
    void repeatedStatusDoesNotWrite() {
        when(accounts.lockById(1L)).thenReturn(account(1L, AccountRole.ADMIN, AccountStatus.ACTIVE, 0));
        when(accounts.lockById(2L)).thenReturn(account(2L, AccountRole.MERCHANT, AccountStatus.DISABLED, 3));
        assertEquals(AccountStatus.DISABLED, service.change(1L, 2L, AccountStatus.DISABLED, "risk").status());
        verifyNoInteractions(cache, actions);
        verify(accounts, never()).changeStatus(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void disableBumpsVersionAndCreatesOneLinkedAction() {
        when(accounts.lockById(1L)).thenReturn(account(1L, AccountRole.ADMIN, AccountStatus.ACTIVE, 0));
        when(accounts.lockById(2L)).thenReturn(account(2L, AccountRole.USER, AccountStatus.ACTIVE, 3));
        when(accounts.changeStatus(2L, 3, "ACTIVE", "DISABLED")).thenReturn(1);
        when(actions.lastAuditId()).thenReturn(51L);
        assertEquals(AccountStatus.DISABLED, service.change(1L, 2L, AccountStatus.DISABLED, "risk").status());
        verify(cache).blockUntilTransactionEnds(2L,
                new AccountAuthCache.State(AccountStatus.DISABLED, 4));
        verify(actions).audit(eq(1L), eq("ACCOUNT_DISABLED"), eq(2L), contains("risk"), any());
        verify(actions).actionItem(eq(51L), eq(1L), eq(2L), eq("risk"), any());
    }

    @Test
    void restoreDoesNotCreateNewAction() {
        when(accounts.lockById(1L)).thenReturn(account(1L, AccountRole.ADMIN, AccountStatus.ACTIVE, 0));
        when(accounts.lockById(2L)).thenReturn(account(2L, AccountRole.USER, AccountStatus.DISABLED, 3));
        when(accounts.changeStatus(2L, 3, "DISABLED", "ACTIVE")).thenReturn(1);
        assertEquals(AccountStatus.ACTIVE, service.change(1L, 2L, AccountStatus.ACTIVE, "reviewed").status());
        verify(actions).audit(eq(1L), eq("ACCOUNT_RESTORED"), eq(2L), contains("reviewed"), any());
        verify(actions, never()).actionItem(any(), any(), any(), any(), any());
        verify(actions).resolveOpen(2L);
    }

    private static UserAccount account(Long id, AccountRole role, AccountStatus status, int version) {
        UserAccount account = new UserAccount();
        account.setUserId(id);
        account.setRole(role);
        account.setStatus(status);
        account.setAuthVersion(version);
        return account;
    }
}
