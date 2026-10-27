package com.shiwushop.order;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountAccess;
import com.shiwushop.common.auth.MerchantShopAccess;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.entity.order.Order;
import com.shiwushop.mapper.order.OrderItemMapper;
import com.shiwushop.mapper.order.OrderMapper;
import com.shiwushop.mapper.shop.ShopMapper;
import com.shiwushop.service.impl.OrderServiceImpl;
import com.shiwushop.vo.order.OrderSummaryVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class OrderSliceUnitTests {
    private final OrderMapper orders = mock(OrderMapper.class);
    private final OrderServiceImpl service = new OrderServiceImpl(orders, mock(OrderItemMapper.class),
            mock(ShopMapper.class), mock(AccountAccess.class), mock(MerchantShopAccess.class),
            mock(PrivacyCipher.class));

    @Test
    void buyerReadsOneExtraRowAndReturnsAStableNextCursor() {
        Order cursor = new Order();
        cursor.setId(42L);
        cursor.setUserId(7L);
        cursor.setCreatedAt(LocalDateTime.of(2026, 10, 26, 12, 0));
        when(orders.selectById(42L)).thenReturn(cursor);
        ArrayList<OrderSummaryVO> rows = new ArrayList<>();
        for (long id = 41; id >= 39; id--) {
            OrderSummaryVO row = new OrderSummaryVO();
            row.setId(id);
            rows.add(row);
        }
        when(orders.selectBuyerSlice(7L, cursor.getCreatedAt(), 42L, 3)).thenReturn(rows);

        var slice = service.list(7L, 42L, 2);
        assertEquals(List.of(41L, 40L), slice.items().stream().map(OrderSummaryVO::getId).toList());
        assertEquals(true, slice.hasMore());
        assertEquals(40L, slice.nextCursor());
        verify(orders).selectBuyerSlice(7L, cursor.getCreatedAt(), 42L, 3);
    }

    @Test
    void cursorFromAnotherBuyerCannotReadOrders() {
        Order cursor = new Order();
        cursor.setUserId(8L);
        when(orders.selectById(42L)).thenReturn(cursor);

        assertEquals(400, assertThrows(BusinessException.class, () -> service.list(7L, 42L, 20)).getCode());
        verify(orders, never()).selectBuyerSlice(anyLong(), any(), any(), anyInt());
    }
}
