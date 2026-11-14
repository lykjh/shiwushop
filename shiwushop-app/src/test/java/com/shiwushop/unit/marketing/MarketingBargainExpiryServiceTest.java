package com.shiwushop.unit.marketing;

import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingBargainExpiryMapper;
import com.shiwushop.marketing.mapper.MarketingBargainHelpMapper;
import com.shiwushop.marketing.service.impl.MarketingBargainExpiryServiceImpl;
import com.shiwushop.marketing.service.impl.MarketingStockLedger;
import com.shiwushop.product.service.TradingProductService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketingBargainExpiryServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-05T20:00:00");
    private final MarketingBargainExpiryMapper mapper = mock(MarketingBargainExpiryMapper.class);
    private final MarketingBargainHelpMapper bargains = mock(MarketingBargainHelpMapper.class);
    private final TradingProductService products = mock(TradingProductService.class);
    private final MarketingStockLedger stock = mock(MarketingStockLedger.class);
    private final MarketingBargainExpiryServiceImpl service = new MarketingBargainExpiryServiceImpl(mapper, bargains,
            products, stock, new MarketingSettings(true),
            Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.ofHours(8)));

    @Test
    void exactDeadlineReleasesHeldStockAndOpenQuotaOnce() {
        when(bargains.lock(7)).thenReturn(new MarketingBargainHelpMapper.Bargain(7L, 11L, 9L, 3L,
                new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("96.00"),
                5, 1, "ACTIVE", NOW, null));
        when(mapper.lockParticipation(11)).thenReturn(new MarketingBargainExpiryMapper.Participation("RESERVED", null));
        when(mapper.scope(9)).thenReturn(new MarketingBargainExpiryMapper.Scope(13L, "activity-code"));
        when(mapper.releaseOpenQuota("activity-code", 3)).thenReturn(1);
        when(mapper.releaseParticipation(11, "RESERVED")).thenReturn(1);
        when(mapper.expire(7, "ACTIVE")).thenReturn(1);

        service.expire(7);

        var order = inOrder(products, stock, mapper);
        order.verify(products).lockSku(13L);
        order.verify(stock).release(9, 11, 1, "BARGAIN:EXPIRE:11");
        order.verify(mapper).releaseOpenQuota("activity-code", 3);
        order.verify(mapper).releaseParticipation(11, "RESERVED");
        order.verify(mapper).expire(7, "ACTIVE");
    }

    @Test
    void convertedOrderIsNeverReleasedByBargainExpiry() {
        when(bargains.lock(7)).thenReturn(new MarketingBargainHelpMapper.Bargain(7L, 11L, 9L, 3L,
                new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("80.00"),
                5, 5, "QUALIFIED", NOW.minusHours(1), NOW));
        when(mapper.lockParticipation(11)).thenReturn(new MarketingBargainExpiryMapper.Participation("QUALIFIED", 17L));

        service.expire(7);

        verifyNoInteractions(products, stock);
        verify(mapper, never()).releaseOpenQuota(anyString(), anyLong());
    }
}
