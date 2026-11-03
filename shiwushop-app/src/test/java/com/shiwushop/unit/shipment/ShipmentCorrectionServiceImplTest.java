package com.shiwushop.unit.shipment;

import com.shiwushop.common.BusinessException;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.trade.dto.shipment.correction.ShipmentCorrectionDecisionDTO;
import com.shiwushop.trade.dto.shipment.correction.ShipmentCorrectionRequestDTO;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.entity.shipment.Shipment;
import com.shiwushop.trade.entity.shipment.ShipmentCorrection;
import com.shiwushop.trade.entity.status.ShipmentStatus;
import com.shiwushop.trade.entity.status.OrderStatus;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentCorrectionMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentMapper;
import com.shiwushop.trade.service.impl.ShipmentCorrectionServiceImpl;
import com.shiwushop.user.service.AccountAccess;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShipmentCorrectionServiceImplTest {
    final MerchantShopAccess ownership = mock(MerchantShopAccess.class);
    final ShopService shops = mock(ShopService.class);
    final OrderMapper orders = mock(OrderMapper.class);
    final ShipmentMapper shipments = mock(ShipmentMapper.class);
    final ShipmentCorrectionMapper corrections = mock(ShipmentCorrectionMapper.class);
    final AccountAccess accounts = mock(AccountAccess.class);
    final ShipmentCorrectionServiceImpl service = new ShipmentCorrectionServiceImpl(
            ownership, shops, orders, shipments, corrections, Clock.systemUTC(), accounts);

    void shippedOrder() {
        var order = new Order(); order.setId(3L); order.setShopId(2L);
        var shipment = new Shipment(); shipment.setId(4L); shipment.setOrderId(3L);
        shipment.setStatus(ShipmentStatus.SHIPPED); shipment.setCarrierName("顺丰"); shipment.setTrackingNo("SF1");
        when(orders.selectForUpdate(3L)).thenReturn(order);
        when(shipments.selectByOrderIdForUpdate(3L)).thenReturn(shipment);
    }
    @Test void samePendingRequestIsIdempotentAndDifferentRequestConflicts() {
        shippedOrder();
        var pending = new ShipmentCorrection(); pending.setId(5L); pending.setStatus("PENDING");
        pending.setOriginalVersion(0); pending.setNewCarrierName("中通"); pending.setNewTrackingNo("ZT1"); pending.setReason("更正");
        when(corrections.pendingForUpdate(4L)).thenReturn(pending);
        assertEquals(5L, service.request(1L, 2L, 3L, new ShipmentCorrectionRequestDTO(0, "中通", "ZT1", "更正")).id());
        assertEquals(409, assertThrows(BusinessException.class, () -> service.request(1L, 2L, 3L,
                new ShipmentCorrectionRequestDTO(0, "圆通", "YT1", "更正"))).getCode());
        verify(corrections, never()).insert(any());
    }
    @Test void staleVersionAndUnchangedTrackingAreRejected() {
        shippedOrder(); when(corrections.version(4L)).thenReturn(1);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.request(1L, 2L, 3L,
                new ShipmentCorrectionRequestDTO(0, "中通", "ZT1", "更正"))).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () -> service.request(1L, 2L, 3L,
                new ShipmentCorrectionRequestDTO(1, "顺丰", "SF1", "更正"))).getCode());
        verify(corrections, never()).insert(any());
    }
    @Test void rejectionAfterTransferDoesNotApplyShipment() {
        shippedOrder();
        var shop = new Shop(); shop.setOwnerUserId(8L); when(shops.lockById(2L)).thenReturn(shop);
        var pending = new ShipmentCorrection(); pending.setId(5L); pending.setShopId(2L); pending.setOrderId(3L);
        pending.setShipmentId(4L); pending.setOriginalVersion(0); pending.setRequestedBy(1L); pending.setStatus("PENDING");
        when(corrections.find(5L)).thenReturn(pending); when(corrections.findForUpdate(5L)).thenReturn(pending);
        assertEquals("REJECTED", service.decide(9L, 5L, new ShipmentCorrectionDecisionDTO(false, null)).status());
        verify(corrections, never()).apply(anyLong(), anyInt(), anyString(), anyString());
        verify(corrections).decide(eq(5L), eq("REJECTED"), eq(9L), any(), isNull());
    }

    @Test void approvalRejectsAnOrderNoLongerPaid() {
        shippedOrder();
        var cancelled = new Order(); cancelled.setId(3L); cancelled.setShopId(2L); cancelled.setStatus(OrderStatus.CANCELLED);
        when(orders.selectForUpdate(3L)).thenReturn(cancelled);
        var shop = new Shop(); shop.setOwnerUserId(1L); when(shops.lockById(2L)).thenReturn(shop);
        var pending = new ShipmentCorrection(); pending.setId(5L); pending.setShopId(2L); pending.setOrderId(3L);
        pending.setShipmentId(4L); pending.setOriginalVersion(0); pending.setRequestedBy(1L); pending.setStatus("PENDING");
        when(corrections.find(5L)).thenReturn(pending); when(corrections.findForUpdate(5L)).thenReturn(pending);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.decide(9L, 5L, new ShipmentCorrectionDecisionDTO(true, null))).getCode());
        verify(corrections, never()).apply(anyLong(), anyInt(), anyString(), anyString());
        verify(corrections, never()).decide(anyLong(), anyString(), anyLong(), any(), any());
    }
}
