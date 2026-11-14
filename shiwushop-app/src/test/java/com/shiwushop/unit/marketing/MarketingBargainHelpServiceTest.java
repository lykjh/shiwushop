package com.shiwushop.unit.marketing;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingBargainHelpMapper;
import com.shiwushop.marketing.service.impl.MarketingBargainHelpServiceImpl;
import com.shiwushop.member.service.MemberAccountService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketingBargainHelpServiceTest {
    private final MarketingBargainHelpMapper mapper = mock(MarketingBargainHelpMapper.class);
    private final AccountSecurity security = mock(AccountSecurity.class);
    private final MemberAccountService members = mock(MemberAccountService.class);
    private final MarketingBargainHelpServiceImpl service = new MarketingBargainHelpServiceImpl(mapper, security, members,
            new MarketingSettings(true), Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.ofHours(8)));
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-05T20:00:00");

    @Test
    void finalFixedStepReachesExactTargetAndStartsOnePurchaseWindow() {
        when(mapper.lock(7)).thenReturn(new MarketingBargainHelpMapper.Bargain(7L, 11L, 9L, 3L,
                new BigDecimal("100.00"), new BigDecimal("99.95"), new BigDecimal("99.96"),
                3, 2, "ACTIVE", NOW.plusHours(1), null));
        when(mapper.scope(9)).thenReturn(new MarketingBargainHelpMapper.Scope(5L, "activity-code", 5));
        when(mapper.lockParticipation(11)).thenReturn("RESERVED");
        when(mapper.useHelpQuota("activity-code", 4, "2026-11-05", 5)).thenReturn(1);
        when(mapper.advance(7, 2, new BigDecimal("99.95"), "QUALIFIED", NOW.plusMinutes(15))).thenReturn(1);
        when(mapper.qualifyParticipation(11, NOW.plusMinutes(15))).thenReturn(1);

        var result = service.help(4L, 7, "help-1");

        assertEquals(new BigDecimal("0.01"), result.decreaseAmount());
        assertEquals(new BigDecimal("99.95"), result.currentAmount());
        assertEquals("QUALIFIED", result.status());
        assertEquals(NOW.plusMinutes(15).atOffset(ZoneOffset.ofHours(8)), result.purchaseExpiresAt());
        verify(mapper).qualifyParticipation(11, NOW.plusMinutes(15));
        verify(mapper).record(7, 4, "help-1", 3, new BigDecimal("99.96"), new BigDecimal("0.01"),
                new BigDecimal("99.95"), NOW);
    }

    @Test
    void sameKeyReplayReturnsOriginalStepWithoutConsumingQuota() {
        when(mapper.lock(7)).thenReturn(new MarketingBargainHelpMapper.Bargain(7L, 11L, 9L, 3L,
                new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("80.00"),
                5, 5, "ORDERED", NOW.minusHours(1), NOW.minusMinutes(5)));
        when(mapper.helpByKey(4, "help-1")).thenReturn(new MarketingBargainHelpMapper.Help(7L, 1,
                new BigDecimal("4.00"), new BigDecimal("96.00"), "help-1", NOW.minusHours(1),
                new BigDecimal("80.00")));

        var result = service.help(4L, 7, "help-1");

        assertEquals(new BigDecimal("96.00"), result.currentAmount());
        assertEquals(1, result.stepNo());
        verify(mapper, never()).ensureHelpQuota(anyLong(), anyString(), anyLong(), anyString());
    }

    @Test
    void newKeyFromSameHelperIsRejected() {
        when(mapper.lock(7)).thenReturn(new MarketingBargainHelpMapper.Bargain(7L, 11L, 9L, 3L,
                new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("96.00"),
                5, 1, "ACTIVE", NOW.plusHours(1), null));
        when(mapper.help(7, 4)).thenReturn(new MarketingBargainHelpMapper.Help(7L, 1,
                new BigDecimal("4.00"), new BigDecimal("96.00"), "help-1", NOW.minusMinutes(1),
                new BigDecimal("80.00")));

        assertEquals(409, assertThrows(BusinessException.class, () -> service.help(4L, 7, "help-2")).getCode());
        verify(mapper, never()).advance(anyLong(), anyInt(), any(), anyString(), any());
    }

    @Test
    void exactDeadlineRejectsNewHelp() {
        when(mapper.lock(7)).thenReturn(new MarketingBargainHelpMapper.Bargain(7L, 11L, 9L, 3L,
                new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("100.00"),
                5, 0, "ACTIVE", NOW, null));
        when(mapper.lockParticipation(11)).thenReturn("RESERVED");

        assertEquals(409, assertThrows(BusinessException.class, () -> service.help(4L, 7, "help-1")).getCode());
        verify(mapper, never()).ensureHelpQuota(anyLong(), anyString(), anyLong(), anyString());
    }

    @Test
    void sameKeyOnDifferentBargainConflictsBeforeQuotaOrPriceChange() {
        when(mapper.lock(8)).thenReturn(new MarketingBargainHelpMapper.Bargain(8L, 12L, 10L, 3L,
                new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("100.00"),
                5, 0, "ACTIVE", NOW.plusHours(1), null));
        when(mapper.helpByKey(4, "same-key")).thenReturn(new MarketingBargainHelpMapper.Help(7L, 1,
                new BigDecimal("4.00"), new BigDecimal("96.00"), "same-key", NOW.minusMinutes(1),
                new BigDecimal("80.00")));

        assertEquals(409, assertThrows(BusinessException.class, () -> service.help(4L, 8, "same-key")).getCode());
        verify(mapper, never()).ensureHelpQuota(anyLong(), anyString(), anyLong(), anyString());
        verify(mapper, never()).advance(anyLong(), anyInt(), any(), anyString(), any());
    }

    @Test
    void differentAccountsMayUseTheSameKey() {
        var first = new MarketingBargainHelpMapper.Bargain(7L, 11L, 9L, 3L,
                new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("100.00"),
                5, 0, "ACTIVE", NOW.plusHours(1), null);
        var second = new MarketingBargainHelpMapper.Bargain(8L, 12L, 10L, 3L,
                new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("100.00"),
                5, 0, "ACTIVE", NOW.plusHours(1), null);
        when(mapper.lock(7)).thenReturn(first);
        when(mapper.lock(8)).thenReturn(second);
        when(mapper.lockParticipation(11)).thenReturn("RESERVED");
        when(mapper.lockParticipation(12)).thenReturn("RESERVED");
        when(mapper.scope(9)).thenReturn(new MarketingBargainHelpMapper.Scope(5L, "activity-code", 5));
        when(mapper.scope(10)).thenReturn(new MarketingBargainHelpMapper.Scope(5L, "activity-code", 5));
        when(mapper.useHelpQuota(eq("activity-code"), anyLong(), eq("2026-11-05"), eq(5))).thenReturn(1);
        when(mapper.advance(anyLong(), eq(0), eq(new BigDecimal("96.00")), eq("ACTIVE"), isNull()))
                .thenReturn(1);

        service.help(4L, 7, "same-key");
        service.help(5L, 8, "same-key");

        verify(mapper).record(7, 4, "same-key", 1, new BigDecimal("100.00"), new BigDecimal("4.00"),
                new BigDecimal("96.00"), NOW);
        verify(mapper).record(8, 5, "same-key", 1, new BigDecimal("100.00"), new BigDecimal("4.00"),
                new BigDecimal("96.00"), NOW);
    }
}
