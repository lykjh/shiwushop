package com.shiwushop.unit.inventory;

import com.shiwushop.common.BusinessException;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.product.dto.inventory.InventoryMovementQuery;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.product.Sku;
import com.shiwushop.product.mapper.product.InventoryMovementMapper;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.product.mapper.product.SkuMapper;
import com.shiwushop.product.service.impl.InventoryMovementServiceImpl;
import com.shiwushop.product.vo.inventory.InventoryMovementVO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryMovementServiceImplTest {
    final MerchantShopAccess shops = mock(MerchantShopAccess.class);
    final SkuMapper skus = mock(SkuMapper.class);
    final ProductMapper products = mock(ProductMapper.class);
    final InventoryMovementMapper movements = mock(InventoryMovementMapper.class);
    final InventoryMovementServiceImpl service = new InventoryMovementServiceImpl(shops, skus, products, movements);
    final InventoryMovementQuery query = new InventoryMovementQuery(null, null, null, null, 1);

    @Test void returnsOneExtraRowAsNextCursor() {
        var sku = new Sku(); sku.setProductId(3L);
        var product = new Product(); product.setShopId(2L);
        when(skus.selectById(4L)).thenReturn(sku);
        when(products.selectById(3L)).thenReturn(product);
        var first = new InventoryMovementVO(9L, "MERCHANT_ADJUST", 1, 2, null, null);
        var second = new InventoryMovementVO(8L, "ORDER_DEDUCT", -1, 1, 6L, null);
        when(movements.selectPage(4L, query, 2)).thenReturn(List.of(first, second));
        var result = service.list(1L, 2L, 4L, query);
        assertEquals(List.of(first), result.items());
        assertTrue(result.hasMore());
        assertEquals(9L, result.nextCursor());
    }

    @Test void rejectsSkuFromAnotherShopWithoutReadingMovements() {
        var sku = new Sku(); sku.setProductId(3L);
        var product = new Product(); product.setShopId(5L);
        when(skus.selectById(4L)).thenReturn(sku);
        when(products.selectById(3L)).thenReturn(product);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.list(1L, 2L, 4L, query)).getCode());
        verifyNoInteractions(movements);
    }
}
