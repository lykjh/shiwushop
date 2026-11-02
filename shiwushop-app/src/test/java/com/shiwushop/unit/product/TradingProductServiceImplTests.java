package com.shiwushop.unit.product;

import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.product.entity.product.ProductOptionValue;
import com.shiwushop.product.mapper.category.CategoryMapper;
import com.shiwushop.product.mapper.product.*;
import com.shiwushop.product.service.CategoryService;
import com.shiwushop.product.service.impl.TradingProductServiceImpl;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradingProductServiceImplTests {
    final SkuMapper skus = mock(SkuMapper.class);
    final ProductMapper products = mock(ProductMapper.class);
    final ShopService shops = mock(ShopService.class);
    final CategoryMapper categories = mock(CategoryMapper.class);
    final SkuOptionValueMapper skuValues = mock(SkuOptionValueMapper.class);
    final ProductOptionValueMapper values = mock(ProductOptionValueMapper.class);
    final CategoryService categoryService = mock(CategoryService.class);
    final TradingProductServiceImpl service = new TradingProductServiceImpl(skus, products, shops,
            categories, skuValues, values, categoryService);

    @Test
    void emptyCartDoesNotReadAnyCatalogTables() {
        assertTrue(service.cartProducts(Map.of()).isEmpty());
        verifyNoInteractions(skus, products, shops, categories, skuValues, values, categoryService);
    }

    @Test
    void missingSkuStillProducesAnUnavailableCartSnapshot() {
        var snapshot = service.cartProducts(Map.of(7L, 2)).get(7L);
        assertNull(snapshot.sku());
        assertNull(snapshot.product());
        assertEquals("商品不存在", snapshot.unavailableReason());
        assertEquals(List.of(), snapshot.optionNames());
        verify(skus).selectBatchIds(List.of(7L));
        verifyNoInteractions(products, shops, categories, skuValues);
    }

    @Test
    void optionNamesKeepStoredOrderAndIgnoreDeletedValues() {
        ProductOptionValue red = new ProductOptionValue(); red.setValueName("红色");
        when(skuValues.selectValueIds(7L)).thenReturn(List.of(4L, 3L));
        when(values.selectById(4L)).thenReturn(red);
        assertEquals(List.of("红色"), service.optionNames(7L));
    }

    @Test
    void checkoutUsesTheSkuRowLockRatherThanAnUnlockedRead() {
        service.lockSku(7L);
        verify(skus).selectForUpdate(7L);
        verify(skus, never()).selectById(7L);
    }
}
