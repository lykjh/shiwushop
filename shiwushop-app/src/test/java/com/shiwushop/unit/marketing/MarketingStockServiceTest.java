package com.shiwushop.unit.marketing;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.ActivityContentHash;
import com.shiwushop.marketing.dto.StockTransfer;
import com.shiwushop.marketing.mapper.MarketingActivityMapper;
import com.shiwushop.marketing.mapper.MarketingStockMapper;
import com.shiwushop.marketing.service.impl.MarketingStockServiceImpl;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.product.Sku;
import com.shiwushop.product.service.ProductStockService;
import com.shiwushop.product.service.TradingProductService;
import com.shiwushop.product.vo.stock.MarketingStockMovement;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketingStockServiceTest {
    private static final String OP_KEY = "0".repeat(64);
    private final MarketingStockMapper mapper = mock(MarketingStockMapper.class);
    private final MarketingActivityMapper activities = mock(MarketingActivityMapper.class);
    private final AccountSecurity security = mock(AccountSecurity.class);
    private final MerchantShopAccess access = mock(MerchantShopAccess.class);
    private final TradingProductService products = mock(TradingProductService.class);
    private final ProductStockService normalStock = mock(ProductStockService.class);
    private final ActivityContentHash hash = mock(ActivityContentHash.class);
    private final MarketingStockServiceImpl service = new MarketingStockServiceImpl(mapper, activities, security, access,
            products, normalStock, new MarketingSettings(true), hash, Clock.systemUTC());

    private void scope() {
        var row = new MarketingStockMapper.Scope(5L, 7L, "RUNNING");
        when(mapper.scope(9)).thenReturn(row);
        when(mapper.lockScope(9)).thenReturn(row);
        var sku = mock(Sku.class);
        when(sku.getProductId()).thenReturn(13L);
        when(products.lockSku(7L)).thenReturn(sku);
        var product = mock(Product.class);
        when(product.getShopId()).thenReturn(2L);
        when(products.findProduct(13L)).thenReturn(product);
        when(hash.hash(any())).thenReturn(new byte[32]);
    }

    @Test
    void allocateLinksNormalAndActivityEntriesInOrder() {
        scope();
        var before = new MarketingStockMapper.Balance(11L, 9L, 0, 0, 0, 0, 0);
        var after = new MarketingStockMapper.Balance(11L, 9L, 2, 2, 0, 0, 0);
        when(mapper.lockBalance(9)).thenReturn(before, after);
        when(normalStock.moveNormalForMarketing(3L, 7L, 9L, -2, OP_KEY, "MARKETING_ALLOCATE"))
                .thenReturn(new MarketingStockMovement(77L, 4, -2, false));
        when(mapper.transfer(11, 2, 2, 0)).thenReturn(1);
        when(mapper.transferEntry(OP_KEY)).thenReturn(null,
                new MarketingStockMapper.TransferEntry(88L, 11L, 77L, 3L, "MARKETING_ALLOCATE", "备货", 2, 2, 0));

        var result = service.transfer(3L, 2L, 9L, "k", new StockTransfer("IN", 2, "备货"));

        assertEquals(88L, result.stockEntryId());
        assertEquals(2, result.pool().availableQuantity());
        verify(hash).hash(List.of(3L, "STOCK_TRANSFER", 9L, "k"));
        var order = inOrder(access, products, mapper, normalStock, activities);
        order.verify(access).requireOwnedForUpdate(3L, 2L, "店铺不存在");
        order.verify(products).lockSku(7L);
        order.verify(mapper).lockScope(9);
        order.verify(normalStock).moveNormalForMarketing(3L, 7L, 9L, -2, OP_KEY, "MARKETING_ALLOCATE");
        order.verify(mapper).recordTransfer(11, OP_KEY, "MARKETING_ALLOCATE", 2, 2, 0,
                77, 3, "备货");
        order.verify(activities).audit(eq(3L), eq(5L), eq("MARKETING_STOCK_TRANSFER"),
                contains("stockEntryId=88"), any());
    }

    @Test
    void replayReturnsExistingEntryAndDoesNotMoveActivityPool() {
        scope();
        var ended = new MarketingStockMapper.Scope(5L, 7L, "ENDED");
        when(mapper.scope(9)).thenReturn(ended);
        when(mapper.lockScope(9)).thenReturn(ended);
        when(normalStock.moveNormalForMarketing(3L, 7L, 9L, 1, OP_KEY, "MARKETING_RELEASE"))
                .thenReturn(new MarketingStockMovement(77L, 9, 1, true));
        when(mapper.lockBalance(9)).thenReturn(new MarketingStockMapper.Balance(11L, 9L, 2, 1, 0, 0, 1));
        when(mapper.transferEntry(OP_KEY))
                .thenReturn(new MarketingStockMapper.TransferEntry(88L, 11L, 77L, 3L,
                        "MARKETING_RELEASE", "返库", 0, -1, 1));

        var result = service.transfer(3L, 2L, 9L, "k", new StockTransfer("OUT", 1, "返库"));

        assertEquals(88L, result.stockEntryId());
        verify(mapper, never()).transfer(anyLong(), anyInt(), anyInt(), anyInt());
        verify(mapper, never()).recordTransfer(anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(),
                anyLong(), anyLong(), anyString());
    }

    @Test
    void replayWithDifferentManualReasonConflicts() {
        scope();
        when(normalStock.moveNormalForMarketing(3L, 7L, 9L, 1, OP_KEY, "MARKETING_RELEASE"))
                .thenReturn(new MarketingStockMovement(77L, 9, 1, true));
        when(mapper.lockBalance(9)).thenReturn(new MarketingStockMapper.Balance(11L, 9L, 2, 1, 0, 0, 1));
        when(mapper.transferEntry(OP_KEY)).thenReturn(new MarketingStockMapper.TransferEntry(88L, 11L, 77L,
                3L, "MARKETING_RELEASE", "第一次原因", 0, -1, 1));

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.transfer(3L, 2L, 9L, "k", new StockTransfer("OUT", 1, "第二次原因"))).getCode());
        verify(mapper, never()).transfer(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void unavailablePoolCannotReleaseAndTransactionMustRollBackNormalMove() {
        scope();
        when(normalStock.moveNormalForMarketing(3L, 7L, 9L, 2, OP_KEY, "MARKETING_RELEASE"))
                .thenReturn(new MarketingStockMovement(77L, 9, 2, false));
        when(mapper.lockBalance(9)).thenReturn(new MarketingStockMapper.Balance(11L, 9L, 1, 1, 0, 0, 0));

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.transfer(3L, 2L, 9L, "k", new StockTransfer("OUT", 2, "返库"))).getCode());
        verify(mapper, never()).recordTransfer(anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(),
                anyLong(), anyLong(), anyString());
    }

    @Test
    void merchantPoolListUsesShopScope() {
        when(mapper.scopeCount(5, 2L)).thenReturn(1);
        when(mapper.pools(5, 2L)).thenReturn(List.of(
                new MarketingStockMapper.PoolRow(11L, 9L, 7L, 2, 1, 1, 0, 0)));

        var rows = service.pools(3L, 2L, 5L);

        assertEquals(1, rows.size());
        assertEquals(1, rows.getFirst().availableQuantity());
        verify(access).requireOwned(3L, 2L, "店铺不存在");
        verify(mapper).pools(5, 2L);
    }

    @Test
    void merchantCannotReadOtherShopsStockEntries() {
        scope();

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.entries(3L, 4L, 9L, null, 20)).getCode());
        verify(mapper, never()).entries(anyLong(), any(), anyInt());
    }
}
