package com.shiwushop.auth;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountAccess;
import com.shiwushop.common.auth.AccountAuthCache;
import com.shiwushop.common.auth.MerchantShopAccess;


import com.shiwushop.entity.status.AccountStatus;
import com.shiwushop.entity.status.AccountRole;

import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.entity.user.UserAccount;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.mapper.user.UserAccountMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountAccessTests {
    private final UserAccountMapper accounts = mock(UserAccountMapper.class);
    private final AccountAuthCache authCache = mock(AccountAuthCache.class);
    private final AccountAccess accountAccess = new AccountAccess(accounts, authCache);

    @Test
    void distinguishesMissingInactiveAndNonMerchantAccounts() {
        assertEquals(401, assertThrows(BusinessException.class,
                () -> accountAccess.checkActive(null)).getCode());
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

    @Test
    void masksForeignShopBeforeCheckingMerchantRole() {
        ShopMapper shops = mock(ShopMapper.class);
        MerchantShopAccess shopAccess = new MerchantShopAccess(shops, accountAccess);
        Shop shop = new Shop();
        shop.setOwnerUserId(2L);
        when(shops.selectById(7L)).thenReturn(shop);

        BusinessException error = assertThrows(BusinessException.class,
                () -> shopAccess.requireOwned(1L, 7L, "商品不存在"));
        assertEquals(404, error.getCode());
        assertEquals("商品不存在", error.getMessage());
        verifyNoInteractions(accounts);
    }
}
