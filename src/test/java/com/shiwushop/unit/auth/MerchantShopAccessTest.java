package com.shiwushop.unit.auth;

import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.merchant.mapper.shop.ShopMapper;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.common.BusinessException;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class MerchantShopAccessTest {
    @Test void masksForeignShopBeforeCheckingMerchantRole() {
        var shops=mock(ShopMapper.class);var accounts=mock(AccountAccess.class);
        var access=new com.shiwushop.merchant.service.impl.MerchantShopAccessImpl(shops,accounts);var shop=new Shop();shop.setOwnerUserId(2L);
        when(shops.selectById(7L)).thenReturn(shop);
        var error=assertThrows(BusinessException.class,()->access.requireOwned(1L,7L,"商品不存在"));
        assertEquals(404,error.getCode());assertEquals("商品不存在",error.getMessage());verifyNoInteractions(accounts);
    }
}
