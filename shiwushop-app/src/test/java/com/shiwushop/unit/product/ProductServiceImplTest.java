package com.shiwushop.unit.product;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.product.service.ProductArchiveConstraint;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.product.Sku;
import com.shiwushop.product.entity.status.ProductStatus;
import com.shiwushop.product.mapper.product.InventoryMovementMapper;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.product.mapper.product.ProductListingSubmissionMapper;
import com.shiwushop.product.mapper.product.SkuMapper;
import com.shiwushop.product.mapper.product.SkuOptionValueMapper;
import com.shiwushop.product.service.impl.ProductServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {
    @BeforeAll
    static void registerSkuMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Sku.class);
    }

    @Mock ProductMapper products;
    @Mock SkuMapper skus;
    @Mock InventoryMovementMapper movements;
    @Mock MerchantShopAccess shops;
    @Mock SkuOptionValueMapper values;

    @Mock ProductListingSubmissionMapper submissions;
    @Mock ProductArchiveConstraint marketing;
    @InjectMocks ProductServiceImpl service;

    @Test
    void inventoryStaffAdjustsStockWithoutPriceOrCatalogPermission() {
        Product product = new Product();
        product.setId(8L);
        product.setShopId(9L);
        product.setStatus(ProductStatus.ON_SALE);
        Sku sku = new Sku();
        sku.setId(7L);
        sku.setProductId(8L);
        sku.setPrice(new BigDecimal("12.00"));
        sku.setStock(5);
        Shop shop = new Shop();
        shop.setId(9L);
        when(products.selectById(8L)).thenReturn(product);
        when(products.selectForUpdate(8L)).thenReturn(product);
        when(shops.requireRoleForUpdate(4L, 9L, "INVENTORY", "商品不存在")).thenReturn(shop);
        when(skus.selectForUpdate(7L)).thenReturn(sku);
        when(skus.update(any(), any())).thenReturn(1);
        when(movements.insertMerchantAdjust(eq(7L), eq(3), eq(8), any())).thenReturn(1);
        when(values.selectValueIds(7L)).thenReturn(List.of());

        var result = service.updateSkuStock(4L, 8L, 7L, 8);

        assertEquals(new BigDecimal("12.00"), result.getPrice());
        assertEquals(8, result.getStock());
        verify(shops, never()).requireRoleForUpdate(4L, 9L, "CATALOG", "商品不存在");
        verify(movements).insertMerchantAdjust(eq(7L), eq(3), eq(8), any());
    }

    @Test
    void listingSubmissionRejectsAnOlderRevisionWithoutWritingReview() {
        Product product = new Product();
        product.setId(8L);
        product.setShopId(9L);
        product.setRevision(5L);
        product.setStatus(ProductStatus.DRAFT);
        Shop shop = new Shop();
        shop.setId(9L);
        when(products.selectById(8L)).thenReturn(product);
        when(products.selectForUpdate(8L)).thenReturn(product);
        when(shops.requireRoleForUpdate(4L, 9L, "CATALOG", "商品不存在")).thenReturn(shop);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.submitListing(4L, 8L, 4L)).getCode());
        verifyNoInteractions(submissions);
    }

    @Test
    void archiveRefusesUnpaidOrderAndLeavesProductUntouched() {
        Product product = new Product();
        product.setId(8L);
        product.setShopId(9L);
        product.setRevision(5L);
        product.setStatus(ProductStatus.OFF_SALE);
        Shop shop = new Shop();
        shop.setId(9L);
        when(products.selectById(8L)).thenReturn(product);
        when(products.selectForUpdate(8L)).thenReturn(product);
        when(shops.requireRoleForUpdate(4L, 9L, "CATALOG", "商品不存在")).thenReturn(shop);
        when(products.selectUnpaidOrderItem(8L)).thenReturn(11L);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.archive(4L, 8L, 5L)).getCode());
        verifyNoInteractions(marketing);
        verify(products, never()).update(any(), any());
    }
}
