package com.shiwushop.unit.account;

import com.shiwushop.common.BusinessException;
import com.shiwushop.user.entity.status.AccountRole;
import com.shiwushop.user.entity.status.AccountStatus;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.mapper.user.UserAccountMapper;
import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.user.service.impl.AdminAccountQueryServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminAccountQueryServiceTest {
    private final AccountAccess access = mock(AccountAccess.class);
    private final UserAccountMapper mapper = mock(UserAccountMapper.class);
    private final AdminAccountQueryServiceImpl service = new AdminAccountQueryServiceImpl(access, mapper);

    @Test
    void rejectsNonAdminBeforeReadingTarget() {
        doThrow(new BusinessException(403, "需要管理员权限")).when(access).requireAdmin(1L);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.detail(1L, 2L)).getCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void returnsOnlyIdentityRoleAndStatus() {
        UserAccount target = new UserAccount();
        target.setUserId(2L);
        target.setRole(AccountRole.MERCHANT);
        target.setStatus(AccountStatus.DISABLED);
        target.setPasswordHash("secret");
        when(mapper.selectById(2L)).thenReturn(target);

        var result = service.detail(1L, 2L);
        assertEquals(2L, result.userId());
        assertEquals(AccountRole.MERCHANT, result.role());
        assertEquals(AccountStatus.DISABLED, result.status());
        assertEquals(3, result.getClass().getRecordComponents().length);
    }

    @Test
    void missingTargetIsNotFound() {
        assertEquals(404, assertThrows(BusinessException.class, () -> service.detail(1L, 2L)).getCode());
    }
}
