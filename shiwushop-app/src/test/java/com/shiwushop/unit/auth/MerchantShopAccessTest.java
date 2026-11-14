package com.shiwushop.unit.auth;

import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.merchant.mapper.shop.ShopMapper;
import com.shiwushop.merchant.mapper.staff.ShopStaffGrantMapper;
import com.shiwushop.merchant.entity.staff.ShopStaffGrant;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class MerchantShopAccessTest {
    @Test
    void masksForeignShopBeforeCheckingMerchantRole() {
        var shops = mock(ShopMapper.class);
        var accounts = mock(AccountAccess.class);
        var access = new com.shiwushop.merchant.service.impl.MerchantShopAccessImpl(
                shops, accounts, mock(ShopStaffGrantMapper.class));
        var shop = new Shop();
        shop.setOwnerUserId(2L);
        when(shops.selectById(7L)).thenReturn(shop);
        var error = assertThrows(BusinessException.class, () -> access.requireOwned(1L, 7L, "商品不存在"));
        assertEquals(404, error.getCode());
        assertEquals("商品不存在", error.getMessage());
        verifyNoInteractions(accounts);
    }

    @Test
    void inventoryGrantIsRecheckedUnderShopAndGrantLocks() {
        var shopMapper = mock(ShopMapper.class);
        var accounts = mock(AccountAccess.class);
        var grants = mock(ShopStaffGrantMapper.class);
        var access = new com.shiwushop.merchant.service.impl.MerchantShopAccessImpl(shopMapper, accounts, grants);
        var shop = new Shop();
        shop.setId(7L);
        shop.setOwnerUserId(2L);
        var grant = new ShopStaffGrant();
        grant.setStatus("ACTIVE");
        grant.setCanInventory(1);
        when(shopMapper.selectForUpdate(7L)).thenReturn(shop);
        when(grants.selectForUpdate(7L, 1L)).thenReturn(grant);

        assertSame(shop, access.requireRoleForUpdate(1L, 7L, "INVENTORY", "商品不存在"));
        verify(accounts).requireActive(1L);
        verify(grants).selectForUpdate(7L, 1L);

        grant.setStatus("REVOKED");
        assertEquals(404, assertThrows(BusinessException.class,
                () -> access.requireRoleForUpdate(1L, 7L, "INVENTORY", "商品不存在")).getCode());
    }
}
