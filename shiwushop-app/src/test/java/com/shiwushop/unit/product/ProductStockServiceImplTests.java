package com.shiwushop.unit.product;

import com.shiwushop.common.BusinessException;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.product.entity.inventory.MarketingInventoryMovement;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.product.Sku;
import com.shiwushop.product.mapper.product.InventoryMovementMapper;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.product.mapper.product.SkuMapper;
import com.shiwushop.product.service.impl.ProductStockServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductStockServiceImplTests {
    final SkuMapper skus = mock(SkuMapper.class);
    final ProductMapper products = mock(ProductMapper.class);
    final InventoryMovementMapper movements = mock(InventoryMovementMapper.class);

    final MerchantShopAccess shops = mock(MerchantShopAccess.class);
    final ProductStockServiceImpl service = new ProductStockServiceImpl(skus, products, movements, shops);

    private void marketingSku(int stock, String status) {
        Sku sku = new Sku();
        sku.setId(7L);
        sku.setProductId(8L);
        sku.setStock(stock);
        sku.setStatus(status);
        Product product = new Product();
        product.setId(8L);
        product.setShopId(9L);
        when(skus.selectById(7L)).thenReturn(sku);
        when(skus.selectForUpdate(7L)).thenReturn(sku);
        when(products.selectById(8L)).thenReturn(product);
        when(skus.selectMarketingBinding(11L, 7L, 9L)).thenReturn(11L);
    }

    @Test
    void marketingAllocationWritesOneNormalMovementAndReturnsOriginalBalanceOnReplay() {
        marketingSku(5, "ACTIVE");
        when(skus.decreaseStock(7L, 2)).thenReturn(1);
        when(movements.insertMarketing(any())).thenAnswer(call -> {
            MarketingInventoryMovement row = call.getArgument(0);
            row.setId(31L);
            return 1;
        });
        var first = service.moveNormalForMarketing(4L, 7L, 11L, -2, "Req:1", "MARKETING_ALLOCATE");
        assertEquals(31L, first.movementId());
        assertEquals(3, first.normalBalanceAfter());
        assertFalse(first.replayed());
        ArgumentCaptor<MarketingInventoryMovement> saved = ArgumentCaptor.forClass(MarketingInventoryMovement.class);
        verify(movements).insertMarketing(saved.capture());
        assertEquals("MARKETING:Req:1", saved.getValue().getOperationKey());
        assertEquals(4L, saved.getValue().getActorUserId());
        when(movements.selectMarketingByKey("MARKETING:Req:1")).thenReturn(saved.getValue());
        var replay = service.moveNormalForMarketing(4L, 7L, 11L, -2, "Req:1", "MARKETING_ALLOCATE");
        assertEquals(31L, replay.movementId());
        assertEquals(3, replay.normalBalanceAfter());
        assertTrue(replay.replayed());
        verify(skus, times(1)).decreaseStock(7L, 2);
        assertEquals(409, assertThrows(BusinessException.class, () ->
                service.moveNormalForMarketing(4L, 7L, 11L, -1, "Req:1", "MARKETING_ALLOCATE")).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () ->
                service.moveNormalForMarketing(5L, 7L, 11L, -2, "Req:1", "MARKETING_ALLOCATE")).getCode());
    }

    @Test
    void marketingReleaseRejectsInvalidDirectionAndNeverOverdrawsNormalStock() {
        marketingSku(0, "STOPPED");
        assertEquals(400, assertThrows(BusinessException.class, () ->
                service.moveNormalForMarketing(4L, 7L, 11L, 0, "K", "MARKETING_RELEASE")).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () ->
                service.moveNormalForMarketing(4L, 7L, 11L, 1, "K", "MARKETING_ALLOCATE")).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () ->
                service.moveNormalForMarketing(4L, 7L, 11L, -1, "K", "MARKETING_ALLOCATE")).getCode());
        verify(movements, never()).insertMarketing(any());
    }

    @Test
    void marketingReleaseReturnsAvailableStockAndKeepsOperationKeysCaseSensitive() {
        marketingSku(4, "STOPPED");
        when(skus.increaseStock(7L, 3)).thenReturn(1);
        when(movements.insertMarketing(any())).thenAnswer(call -> {
            MarketingInventoryMovement row = call.getArgument(0);
            row.setId(32L);
            return 1;
        });

        var result = service.moveNormalForMarketing(4L, 7L, 11L, 3, "Release:A", "MARKETING_RELEASE");

        assertEquals(7, result.normalBalanceAfter());
        assertEquals(3, result.normalDelta());
        assertFalse(result.replayed());
        verify(skus).increaseStock(7L, 3);
        ArgumentCaptor<MarketingInventoryMovement> saved = ArgumentCaptor.forClass(MarketingInventoryMovement.class);
        verify(movements).insertMarketing(saved.capture());
        assertEquals("MARKETING:Release:A", saved.getValue().getOperationKey());
    }

    @Test
    void concurrentKeyCollisionAcrossSkusIsAConflict() {
        marketingSku(5, "ACTIVE");
        when(skus.decreaseStock(7L, 1)).thenReturn(1);
        when(movements.insertMarketing(any())).thenThrow(new DuplicateKeyException("operation_key"));

        assertEquals(409, assertThrows(BusinessException.class, () ->
                service.moveNormalForMarketing(4L, 7L, 11L, -1, "shared-key", "MARKETING_ALLOCATE"))
                .getCode());
    }

    @Test
    void allocationAboveCartQuantityLimitUsesActualAvailableStock() {
        marketingSku(20_000, "ACTIVE");
        when(skus.decreaseStock(7L, 10_001)).thenReturn(1);
        when(movements.insertMarketing(any())).thenAnswer(call -> {
            MarketingInventoryMovement row = call.getArgument(0);
            row.setId(33L);
            return 1;
        });

        var result = service.moveNormalForMarketing(4L, 7L, 11L, -10_001,
                "larger-pool", "MARKETING_ALLOCATE");
        assertEquals(9_999, result.normalBalanceAfter());
        verify(skus).decreaseStock(7L, 10_001);
    }

    @Test
    void orderDeductionWritesTheMatchingLedgerEntry() {
        when(skus.decreaseStock(7L, 2)).thenReturn(1);
        service.deductForOrder(7L, 9L, 2, 8, "CHECKOUT_DEDUCT:5:7");
        var order = inOrder(skus, movements);
        order.verify(skus).decreaseStock(7L, 2);
        order.verify(movements).insertOrderDeduct(7L, 9L, -2, 8, "CHECKOUT_DEDUCT:5:7");
    }

    @Test
    void insufficientStockNeverWritesALedgerEntry() {
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.deductForOrder(7L, 9L, 2, 8, "CHECKOUT_DEDUCT:5:7")).getCode());
        verifyNoInteractions(movements);
    }

    @Test
    void cancellationAndRefundKeepTheirDifferentLedgerReasons() {
        when(skus.increaseStock(7L, 2)).thenReturn(1);
        when(skus.selectStock(7L)).thenReturn(10);
        service.returnCancelledOrder(7L, 9L, 2, "ORDER_CANCEL_RETURN:5:9");
        service.returnRefund(7L, 9L, 2, "REFUND_SUCCESS:6:9");
        verify(movements).insertOrderCancelReturn(7L, 9L, 2, 10, "ORDER_CANCEL_RETURN:5:9");
        verify(movements).insertRefundReturn(7L, 9L, 2, 10, "REFUND_SUCCESS:6:9");
    }

    @Test
    void saleUpdatesOnlyTheSoldCount() {
        service.recordSale(7L, 2);
        verify(products).increaseSoldCount(7L, 2);
        verifyNoInteractions(skus, movements);
    }
}
