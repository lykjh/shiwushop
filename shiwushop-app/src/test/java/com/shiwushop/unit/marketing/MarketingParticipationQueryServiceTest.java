package com.shiwushop.unit.marketing;

import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingParticipationQueryMapper;
import com.shiwushop.marketing.service.impl.MarketingParticipationQueryServiceImpl;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketingParticipationQueryServiceTest {
    private final MarketingParticipationQueryMapper mapper = mock(MarketingParticipationQueryMapper.class);
    private final MarketingParticipationQueryServiceImpl service =
            new MarketingParticipationQueryServiceImpl(mapper, new MarketingSettings(true));

    @Test
    void participationCursorUsesOnlyAuthenticatedUserAndHidesLaterRows() {
        var expires = LocalDateTime.parse("2026-11-05T21:00:00");
        var first = new MarketingParticipationQueryMapper.ParticipationRow(9L, "GROUP", 5L, 7L, 11L,
                13L, null, 1, "PAID", expires, expires.minusMinutes(10));
        var extra = new MarketingParticipationQueryMapper.ParticipationRow(8L, "GROUP", 5L, 7L, 12L,
                13L, null, 1, "PAID", expires, expires.minusMinutes(8));
        when(mapper.participations(3, "GROUP", "PAID", null, 2)).thenReturn(List.of(first, extra));

        var page = service.participations(3, null, 1, "GROUP", "PAID");

        assertEquals(1, page.items().size());
        assertEquals(9L, page.items().getFirst().id());
        assertEquals(9L, page.nextAfterId());
        assertTrue(page.hasMore());
        verify(mapper).participations(3, "GROUP", "PAID", null, 2);
    }

    @Test
    void redemptionPageReturnsSpentAndReturnedUnits() {
        var created = LocalDateTime.parse("2026-11-05T20:00:00");
        when(mapper.redemptions(3, "RETURNED", null, 21)).thenReturn(List.of(
                new MarketingParticipationQueryMapper.RedemptionRow(11L, 9L, 7L, 50000, 50000,
                        "RETURNED", created)));

        var page = service.redemptions(3, null, 20, "RETURNED");

        assertEquals(50000, page.items().getFirst().returnedPointUnits());
        assertFalse(page.hasMore());
        verify(mapper).redemptions(3, "RETURNED", null, 21);
    }
}
