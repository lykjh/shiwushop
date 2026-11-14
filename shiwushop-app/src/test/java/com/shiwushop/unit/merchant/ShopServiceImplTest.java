package com.shiwushop.unit.merchant;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.media.MediaAssetAccess;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.merchant.mapper.shop.ShopMapper;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.merchant.service.ShopCategoryAccess;
import com.shiwushop.merchant.mapper.staff.ShopStaffGrantMapper;
import com.shiwushop.merchant.service.impl.ShopServiceImpl;
import com.shiwushop.user.service.AccountAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopServiceImplTest {
    @Test
    void lockByIdReturnsLockedShopOrNotFound() {
        var mapper = mock(ShopMapper.class);
        var service = new ShopServiceImpl(mapper, mock(AccountAccess.class), mock(MerchantShopAccess.class),
                mock(MediaAssetAccess.class), mock(ShopCategoryAccess.class), mock(OutboxEventMapper.class),
                mock(ShopStaffGrantMapper.class));
        var shop = new Shop();
        shop.setId(1L);
        when(mapper.selectForUpdate(1L)).thenReturn(shop);
        assertSame(shop, service.lockById(1L));
        assertEquals(404, assertThrows(BusinessException.class, () -> service.lockById(2L)).getCode());
        verify(mapper).selectForUpdate(1L);
        verify(mapper).selectForUpdate(2L);
    }

    @Test
    void ownerTransferRevokesExistingStaffInTheSameServiceOperation() {
        var mapper = mock(ShopMapper.class);
        var accounts = mock(AccountAccess.class);
        var grants = mock(ShopStaffGrantMapper.class);
        var service = new ShopServiceImpl(mapper, accounts, mock(MerchantShopAccess.class),
                mock(MediaAssetAccess.class), mock(ShopCategoryAccess.class), mock(OutboxEventMapper.class), grants);
        var shop = new Shop();
        shop.setId(9L);
        shop.setOwnerUserId(4L);
        shop.setEnabled(1);
        when(mapper.selectForUpdate(9L)).thenReturn(shop);

        service.changeOwner(9L, 7L);

        var order = inOrder(mapper, grants, accounts);
        order.verify(mapper).selectForUpdate(9L);
        order.verify(mapper).updateById(shop);
        order.verify(grants).revokeShop(9L, null);
        order.verify(accounts).promoteMerchant(7L);
        assertEquals(7L, shop.getOwnerUserId());
    }
}
