package com.shiwushop.unit.merchant;

import com.shiwushop.common.BusinessException;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.merchant.entity.staff.ShopStaffGrant;
import com.shiwushop.merchant.mapper.shop.ShopMapper;
import com.shiwushop.merchant.mapper.staff.ShopStaffGrantMapper;
import com.shiwushop.merchant.service.impl.ShopStaffServiceImpl;
import com.shiwushop.user.service.AccountAccess;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShopStaffServiceImplTest {
    private final ShopMapper shops = mock(ShopMapper.class);
    private final ShopStaffGrantMapper grants = mock(ShopStaffGrantMapper.class);
    private final AccountAccess accounts = mock(AccountAccess.class);
    private final ShopStaffServiceImpl service = new ShopStaffServiceImpl(shops, grants, accounts);

    @Test
    void onlyCurrentOwnerGrantsAndRevokesAnInventoryRole() {
        Shop shop = new Shop();
        shop.setId(9L);
        shop.setOwnerUserId(4L);
        when(shops.selectForUpdate(9L)).thenReturn(shop);
        when(grants.insert(any())).thenReturn(1);

        var granted = service.grant(4L, 9L, 7L, Set.of("INVENTORY"));

        assertEquals(Set.of("INVENTORY"), granted.roles());
        assertEquals(4L, granted.grantedByUserId());
        verify(grants).insert(any());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.grant(5L, 9L, 8L, Set.of("INVENTORY"))).getCode());

        ShopStaffGrant persisted = new ShopStaffGrant();
        persisted.setShopId(9L);
        persisted.setUserId(7L);
        persisted.setStatus("ACTIVE");
        persisted.setVersion(0L);
        persisted.setCanCatalog(0);
        persisted.setCanInventory(1);
        persisted.setCanFulfillment(0);
        when(grants.selectForUpdate(9L, 7L)).thenReturn(persisted);
        when(grants.revoke(9L, 7L, 4L)).thenReturn(1);
        assertEquals("REVOKED", service.revoke(4L, 9L, 7L).status());
        verify(grants).revoke(9L, 7L, 4L);
    }
}
