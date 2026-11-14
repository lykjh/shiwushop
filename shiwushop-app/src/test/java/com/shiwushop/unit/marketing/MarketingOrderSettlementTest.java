package com.shiwushop.unit.marketing;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.ActivityContentHash;
import com.shiwushop.marketing.mapper.*;
import com.shiwushop.marketing.service.impl.*;
import com.shiwushop.member.service.MemberPointRedemptionService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketingOrderSettlementTest {
    @Test
    void paidSeatAfterDeadlineFailsGroupAndKeepsRefundResponsibility() {
        var orders = mock(MarketingOrderMapper.class);
        var sources = mock(MarketingOrderSourceMapper.class);
        var groups = mock(MarketingGroupMapper.class);
        var clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        var now = LocalDateTime.now(clock);
        var settlement = new MarketingOrderSettlementImpl(orders, sources, groups, mock(MarketingStockMapper.class),
                mock(MarketingStockLedger.class), mock(MemberPointRedemptionService.class),
                mock(ActivityContentHash.class), mock(MarketingBudgetHoldMapper.class),
                mock(MarketingActivityMapper.class), mock(MarketingCouponMapper.class),
                mock(CouponBudgetCommitments.class), clock, mock(ObjectMapper.class));
        when(sources.association(42)).thenReturn(new MarketingOrderSourceMapper.Association(9L, 10L, null));
        when(sources.participation(9)).thenReturn(new MarketingOrderSourceMapper.Participation(
                9L, 7L, 4L, "request", new byte[32], 42L, 1, "RESERVED", now.plusMinutes(1), 10L));
        when(sources.scope(7)).thenReturn(new MarketingOrderSourceMapper.Scope(
                6L, 7L, 3L, 5L, 8L, "GROUP", "RUNNING", "SALE", "ON_SALE", "ACTIVE",
                now.minusHours(1), now.plusHours(1), new BigDecimal("10.00"), null, 2, 60, null, null));
        when(orders.scopeDelta(7, -1, 1)).thenReturn(1);
        when(sources.group(10)).thenReturn(new MarketingOrderSourceMapper.GroupRoot(
                10L, 7L, 4L, 2, 1, 1, "FORMING", now.minusMinutes(1), now));
        when(groups.fail(10, now)).thenReturn(1);
        when(sources.finishParticipation(eq(9L), eq(42L), eq("REFUND_PENDING"), any())).thenReturn(1);

        assertDoesNotThrow(() -> settlement.finish(42, 4, true));
        verify(groups).fail(10, now);
        verify(sources).finishParticipation(eq(9L), eq(42L), eq("REFUND_PENDING"), any());
    }

    @Test
    void changedActivityPriceIsRejectedBeforeBindingOrReserving() {
        var orders = mock(MarketingOrderMapper.class);
        var sources = mock(MarketingOrderSourceMapper.class);
        var stock = mock(MarketingStockLedger.class);
        var settlement = new MarketingOrderSettlementImpl(orders, sources, mock(MarketingGroupMapper.class),
                mock(MarketingStockMapper.class),
                stock, mock(MemberPointRedemptionService.class), mock(ActivityContentHash.class),
                mock(MarketingBudgetHoldMapper.class), mock(MarketingActivityMapper.class),
                mock(MarketingCouponMapper.class), mock(CouponBudgetCommitments.class),
                Clock.systemDefaultZone(), mock(ObjectMapper.class));
        var now = LocalDateTime.now();
        var original = new MarketingOrderSourceMapper.Scope(6L, 7L, 3L, 5L, 8L, "FLASH",
                "RUNNING", "SALE", "ON_SALE", "ACTIVE", now.minusHours(1), now.plusHours(1),
                new BigDecimal("10.00"), null, null, null, null, null);
        var changed = new MarketingOrderSourceMapper.Scope(6L, 7L, 3L, 5L, 8L, "FLASH",
                "RUNNING", "SALE", "ON_SALE", "ACTIVE", now.minusHours(1), now.plusHours(1),
                new BigDecimal("9.00"), null, null, null, null, null);
        when(sources.participation(9)).thenReturn(new MarketingOrderSourceMapper.Participation(
                9L, 7L, 4L, "request", new byte[32], null, 1, "RESERVED", now.plusHours(1), null));
        when(sources.scope(7)).thenReturn(original);
        when(sources.orderFacts(42)).thenReturn(new MarketingOrderSourceMapper.OrderFacts(
                3L, 4L, "FLASH", new BigDecimal("10.00"), 1, 8L, 1,
                new BigDecimal("10.00"), new BigDecimal("10.00"), null, 11L));
        when(sources.lockScope(7)).thenReturn(changed);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> settlement.reserve(42, 3, 4, now.plusMinutes(30), null,
                        Map.of(8L, 11L), List.of(), 9L)).getCode());
        verify(sources, never()).bind(anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(stock);
    }
}
