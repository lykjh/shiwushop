package com.shiwushop.unit.product;

import com.shiwushop.common.BusinessException;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.product.entity.product.ProductOptionValue;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.product.Sku;
import com.shiwushop.product.entity.status.ProductStatus;
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
        ProductOptionValue red = new ProductOptionValue();
        red.setValueName("红色");
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

    @Test
    void stoppedSkuCannotBePurchasedEvenWhenProductIsOnSaleAndStockRemains() {
        Product product = new Product();
        product.setStatus(ProductStatus.ON_SALE);
        Sku sku = new Sku();
        sku.setStatus("STOPPED");
        sku.setStock(5);

        assertEquals("SKU已停售", service.unavailableReason(sku, product, 1));
        verifyNoInteractions(shops, categoryService);
    }

    @Test
    void marketableProductUsesCurrentReadAndChecksShopAndStatus() {
        Product product = new Product();
        product.setShopId(9L);
        product.setStatus(ProductStatus.OFF_SALE);
        when(products.selectForUpdate(8L)).thenReturn(null, product, product, product);

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.requireMarketableProductForUpdate(9L, 8L)).getCode());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.requireMarketableProductForUpdate(10L, 8L)).getCode());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.requireMarketableProductForUpdate(9L, 8L)).getCode());
        product.setStatus(ProductStatus.ON_SALE);
        assertSame(product, service.requireMarketableProductForUpdate(9L, 8L));
        verify(products, times(4)).selectForUpdate(8L);
        verify(products, never()).selectById(8L);
        verifyNoInteractions(skus);
    }
}
