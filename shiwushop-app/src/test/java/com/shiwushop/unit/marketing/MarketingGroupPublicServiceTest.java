package com.shiwushop.unit.marketing;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingGroupMapper;
import com.shiwushop.marketing.service.impl.MarketingGroupPublicServiceImpl;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketingGroupPublicServiceTest {
    private final MarketingGroupMapper mapper = mock(MarketingGroupMapper.class);
    private final MarketingGroupPublicServiceImpl service = new MarketingGroupPublicServiceImpl(mapper,
            new MarketingSettings(true), Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.ofHours(8)));

    @Test
    void availableGroupsShowOnlyCapacityAndNoMemberIdentity() {
        var opened = LocalDateTime.parse("2026-11-05T20:00:00");
        var row = new MarketingGroupMapper.GroupRow(3L, 5L, 7L, 9L, "FORMING", 4, 1, 2,
                opened, opened.plusHours(2), null);
        when(mapper.available(5, 7, null, 21, opened)).thenReturn(List.of(row));

        var page = service.available(5, 7, null, 20);

        assertEquals(1, page.items().size());
        assertEquals(1, page.items().getFirst().remainingSeats());
        assertEquals(2, page.items().getFirst().paidMembers());
        assertFalse(page.hasMore());
    }

    @Test
    void unopenedGroupIsNotPublic() {
        assertEquals(404, assertThrows(BusinessException.class, () -> service.detail(3)).getCode());
    }
}
