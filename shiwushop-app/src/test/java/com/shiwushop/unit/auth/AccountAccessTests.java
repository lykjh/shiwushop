package com.shiwushop.unit.auth;

import com.shiwushop.common.BusinessException;
import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.user.auth.AccountAuthCache;
import com.shiwushop.merchant.service.MerchantShopAccess;


import com.shiwushop.user.entity.status.AccountStatus;
import com.shiwushop.user.entity.status.AccountRole;

import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.merchant.mapper.shop.ShopMapper;
import com.shiwushop.user.mapper.user.UserAccountMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountAccessTests {
    @Test
    void shopOwnerQualificationRejectsMissingDisabledAndAdminAccounts() {
        assertEquals(404, assertThrows(BusinessException.class,
                () -> accountAccess.requireShopOwner(1L, "经营者不存在")).getCode());
        UserAccount owner = new UserAccount();
        owner.setStatus(AccountStatus.ACTIVE); owner.setRole(AccountRole.ADMIN);
        when(accounts.selectById(1L)).thenReturn(owner);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> accountAccess.requireShopOwner(1L, "经营者不存在")).getCode());
        owner.setRole(AccountRole.USER);
        assertSame(owner, accountAccess.requireShopOwner(1L, "经营者不存在"));
        owner.setStatus(AccountStatus.DISABLED);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> accountAccess.requireShopOwner(1L, "经营者不存在")).getCode());
    }
    private final UserAccountMapper accounts = mock(UserAccountMapper.class);
    private final AccountAuthCache authCache = mock(AccountAuthCache.class);
    private final AccountAccess accountAccess = new com.shiwushop.user.service.impl.AccountAccessImpl(accounts, authCache);

    @Test
    void distinguishesMissingInactiveAndNonMerchantAccounts() {
        assertEquals(401, assertThrows(BusinessException.class,
                () -> accountAccess.checkActive(1L)).getCode());

        UserAccount account = new UserAccount();
        account.setStatus(AccountStatus.ACTIVE);
        account.setRole(AccountRole.USER);
        when(accounts.selectById(1L)).thenReturn(account);
        when(authCache.current(1L)).thenReturn(new AccountAuthCache.State(AccountStatus.ACTIVE, 1));
        accountAccess.checkActive(1L);
        assertSame(account, accountAccess.requireActive(1L));
        assertEquals(403, assertThrows(BusinessException.class,
                () -> accountAccess.requireMerchant(1L)).getCode());

        account.setRole(AccountRole.MERCHANT);
        accountAccess.requireMerchant(1L);
    }

}
