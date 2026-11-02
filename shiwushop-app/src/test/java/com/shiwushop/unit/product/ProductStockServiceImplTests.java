package com.shiwushop.unit.product;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.product.mapper.product.InventoryMovementMapper;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.product.mapper.product.SkuMapper;
import com.shiwushop.product.service.impl.ProductStockServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductStockServiceImplTests {
    final SkuMapper skus = mock(SkuMapper.class);
    final ProductMapper products = mock(ProductMapper.class);
    final InventoryMovementMapper movements = mock(InventoryMovementMapper.class);
    final OutboxEventMapper outbox = mock(OutboxEventMapper.class);
    final ProductStockServiceImpl service = new ProductStockServiceImpl(skus, products, movements, outbox);

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
    void searchRefreshDeduplicatesProductsAndPropagatesPersistenceFailure() {
        when(outbox.insertSearchProductRefresh(7L)).thenReturn(1);
        service.refreshProducts(List.of(7L, 7L));
        verify(outbox).insertSearchProductRefresh(7L);
        assertThrows(IllegalStateException.class, () -> service.refreshProducts(List.of(8L)));
    }

    @Test
    void saleUpdatesOnlyTheSoldCount() {
        service.recordSale(7L, 2);
        verify(products).increaseSoldCount(7L, 2);
        verifyNoInteractions(skus, movements, outbox);
    }
}
