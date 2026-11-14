package com.shiwushop.unit.marketing;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.marketing.ActivityContentHash;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingBargainStartMapper;
import com.shiwushop.marketing.service.MarketingBargainQueryService;
import com.shiwushop.marketing.service.impl.MarketingBargainStartServiceImpl;
import com.shiwushop.marketing.service.impl.MarketingStockLedger;
import com.shiwushop.marketing.vo.BargainPublicVO;
import com.shiwushop.member.model.MemberAccount;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.product.Sku;
import com.shiwushop.product.entity.status.ProductStatus;
import com.shiwushop.product.service.TradingProductService;
import com.shiwushop.user.service.AccountTradeGate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketingBargainStartServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-05T20:00:00");
    private final MarketingBargainStartMapper mapper = mock(MarketingBargainStartMapper.class);
    private final MarketingStockLedger stock = mock(MarketingStockLedger.class);
    private final MarketingBargainQueryService queries = mock(MarketingBargainQueryService.class);
    private final AccountSecurity security = mock(AccountSecurity.class);
    private final AccountTradeGate tradeGate = mock(AccountTradeGate.class);
    private final MemberAccountService members = mock(MemberAccountService.class);
    private final ShopService shops = mock(ShopService.class);
    private final TradingProductService products = mock(TradingProductService.class);
    private final ActivityContentHash hash = mock(ActivityContentHash.class);
    private final ObjectMapper json = mock(ObjectMapper.class);
    private final MarketingBargainStartServiceImpl service = new MarketingBargainStartServiceImpl(mapper, stock,
            queries, security, tradeGate, members, shops, products, new MarketingSettings(true), hash, json,
            Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.ofHours(8)));

    private void ready() {
        when(mapper.skuId(9)).thenReturn(7L);
        var sku = mock(Sku.class);
        when(sku.getProductId()).thenReturn(8L);
        when(sku.getStatus()).thenReturn("ACTIVE");
        when(products.findSku(7L)).thenReturn(sku);
        when(products.lockSku(7L)).thenReturn(sku);
        var product = mock(Product.class);
        when(product.getShopId()).thenReturn(2L);
        when(product.getStatus()).thenReturn(ProductStatus.ON_SALE);
        when(products.findProduct(8L)).thenReturn(product);
        var shop = mock(Shop.class);
        when(shop.getId()).thenReturn(2L);
        when(shop.isTradingEnabled()).thenReturn(true);
        when(shops.lockById(2L)).thenReturn(shop);
        var account = mock(MemberAccount.class);
        when(account.getLevelCode()).thenReturn("NORMAL");
        when(members.initializeAndLock(3L)).thenReturn(account);
        when(mapper.lockScope(9)).thenReturn(new MarketingBargainStartMapper.Scope(5L, "activity-code", 7L,
                "RUNNING", NOW.minusHours(1), NOW.plusHours(1), "[\"NORMAL\"]",
                new BigDecimal("100.00"), new BigDecimal("80.00"), 5, 3600, 10));
        when(json.readValue("[\"NORMAL\"]", String[].class)).thenReturn(new String[]{"NORMAL"});
        when(hash.hash(any())).thenReturn(new byte[32]);
    }

    @Test
    void startReservesOneActivityUnitAndOneOpenQuota() {
        ready();
        when(mapper.lastId()).thenReturn(101L, 201L);
        when(mapper.reserveOpenQuota("activity-code", 3L)).thenReturn(1);
        var expected = mock(BargainPublicVO.Mine.class);
        when(queries.mineDetail(3, 201)).thenReturn(expected);

        var result = service.start(3L, 9, "start-1");

        assertSame(expected, result);
        var accountOrder = inOrder(tradeGate, shops);
        accountOrder.verify(tradeGate).requireCanStartTrade(3L);
        accountOrder.verify(shops).lockById(2L);
        verify(mapper).insertParticipation(9, 3, "start-1", new byte[32], NOW.plusHours(1));
        verify(stock).reserve(9, 101, 1, "BARGAIN:START:101");
        var order = inOrder(stock, mapper);
        order.verify(stock).reserve(9, 101, 1, "BARGAIN:START:101");
        order.verify(mapper).reserveOpenQuota("activity-code", 3);
        order.verify(mapper).insertBargain(101, 9, 3, new BigDecimal("100.00"),
                new BigDecimal("80.00"), 5, NOW.plusHours(1));
    }

    @Test
    void pendingDeletionRejectsBeforeShopLock() {
        doThrow(new BusinessException(409, "ACCOUNT_DELETION_PENDING"))
                .when(tradeGate).requireCanStartTrade(3L);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.start(3L, 9, "start-1")).getCode());
        verifyNoInteractions(shops, members, stock);
    }

    @Test
    void sameKeyForDifferentScopeConflictsBeforeReservingStock() {
        ready();
        when(mapper.participation(3, "start-1"))
                .thenReturn(new MarketingBargainStartMapper.Participation(101L, 10L, new byte[32]));

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.start(3L, 9, "start-1")).getCode());
        verify(stock, never()).reserve(anyLong(), anyLong(), anyInt(), anyString());
    }
}
