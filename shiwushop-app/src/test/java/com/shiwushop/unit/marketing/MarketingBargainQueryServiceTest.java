package com.shiwushop.unit.marketing;

import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingBargainQueryMapper;
import com.shiwushop.marketing.service.impl.MarketingBargainQueryServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketingBargainQueryServiceTest {
    private final MarketingBargainQueryMapper mapper = mock(MarketingBargainQueryMapper.class);
    private final MarketingBargainQueryServiceImpl service = new MarketingBargainQueryServiceImpl(mapper,
            new MarketingSettings(true), Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.ofHours(8)));

    @Test
    void ownQualifiedBargainCannotPurchaseWhenSkuIsStopped() {
        var row = new MarketingBargainQueryMapper.BargainRow(3L, 5L, 7L, 9L, 11L, null,
                "QUALIFIED", new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("80.00"),
                5, 5, LocalDateTime.parse("2026-11-05T19:00:00"),
                LocalDateTime.parse("2026-11-05T20:15:00"), "STOPPED", "ON_SALE", true);
        when(mapper.mine(13, null, null, 21)).thenReturn(List.of(row));

        var page = service.mine(13, null, 20, null);

        assertFalse(page.items().getFirst().canPurchase());
        assertEquals(11L, page.items().getFirst().participationId());
        verify(mapper).mine(13, null, null, 21);
    }

    @Test
    void publicViewDoesNotIncludeOwnerOrHelperAccounts() {
        var row = new MarketingBargainQueryMapper.BargainRow(3L, 5L, 7L, 9L, 11L, null,
                "ACTIVE", new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("96.00"),
                5, 1, LocalDateTime.parse("2026-11-06T20:00:00"), null, "ACTIVE", "ON_SALE", true);
        when(mapper.find(3)).thenReturn(row);

        var view = service.detail(3);

        assertEquals(new BigDecimal("96.00"), view.currentAmount());
        assertEquals(7L, view.activitySkuId());
    }

    @Test
    void convertedBargainDoesNotOfferAnotherPurchase() {
        var row = new MarketingBargainQueryMapper.BargainRow(3L, 5L, 7L, 9L, 11L, 17L,
                "QUALIFIED", new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("80.00"),
                5, 5, LocalDateTime.parse("2026-11-05T19:00:00"),
                LocalDateTime.parse("2026-11-05T20:15:00"), "ACTIVE", "ON_SALE", true);
        when(mapper.findMine(3, 13)).thenReturn(row);

        assertFalse(service.mineDetail(13, 3).canPurchase());
    }
}
