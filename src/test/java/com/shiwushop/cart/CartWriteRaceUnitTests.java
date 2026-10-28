package com.shiwushop.cart;

import com.shiwushop.common.auth.AccountAccess;
import com.shiwushop.common.BusinessException;
import com.shiwushop.entity.cart.CartItem;
import com.shiwushop.mapper.cart.CartItemMapper;
import com.shiwushop.mapper.product.SkuMapper;
import com.shiwushop.service.impl.CartServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartWriteRaceUnitTests {
    @Mock CartItemMapper items;
    @Mock SkuMapper skus;
    @Mock AccountAccess accounts;
    @InjectMocks CartServiceImpl service;

    @Test
    void updateRejectsItemDeletedAfterOwnershipRead() {
        CartItem item = new CartItem();
        item.setId(11L);
        item.setUserId(7L);
        item.setSkuId(19L);
        item.setQuantity(1);
        when(items.selectOne(any())).thenReturn(item);
        when(items.updateById(item)).thenReturn(0);

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.updateQuantity(7L, 11L, 2)).getCode());
        verify(items).updateById(item);
    }

    @Test
    void deleteRejectsItemDeletedAfterOwnershipRead() {
        CartItem item = new CartItem();
        item.setId(11L);
        item.setUserId(7L);
        when(items.selectOne(any())).thenReturn(item);
        when(items.deleteById(11L)).thenReturn(0);

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.delete(7L, 11L)).getCode());
        verify(items).deleteById(11L);
    }
}
