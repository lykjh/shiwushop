package com.shiwushop.unit.marketing;

import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingPointProductMapper;
import com.shiwushop.marketing.service.impl.MarketingPointProductServiceImpl;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketingPointProductServiceTest {
    private final MarketingPointProductMapper mapper = mock(MarketingPointProductMapper.class);
    private final MarketingPointProductServiceImpl service = new MarketingPointProductServiceImpl(mapper,
            new MarketingSettings(true), Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.ofHours(8)));

    @Test
    void availableProductsExposePointsAndActivityStockWithoutCost() {
        var start = LocalDateTime.parse("2026-11-05T20:00:00");
        var row = new MarketingPointProductMapper.PointProductRow(5L, 7L, 9L, "兑换商品", 11L, 13L,
                50000, 2, 3, start, start.plusDays(1));
        when(mapper.available(13L, null, 21, start)).thenReturn(List.of(row));

        var page = service.available(13L, null, 20);

        assertEquals(50000, page.items().getFirst().pointCostUnits());
        assertEquals(3, page.items().getFirst().availableQuantity());
        assertEquals(13L, page.items().getFirst().shopId());
        assertFalse(page.hasMore());
    }
}
