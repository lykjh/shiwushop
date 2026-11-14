package com.shiwushop.unit.member;

import com.shiwushop.member.MemberSettings;
import com.shiwushop.member.dto.RewardLine;
import com.shiwushop.member.mapper.MemberMapper;
import com.shiwushop.member.mapper.MemberPointRedemptionMapper;
import com.shiwushop.member.model.MemberAccount;
import com.shiwushop.member.model.MemberPointEntry;
import com.shiwushop.member.model.MemberPointLot;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.member.service.impl.MemberGrowthLedger;
import com.shiwushop.member.service.impl.MemberPointLedger;
import com.shiwushop.member.service.impl.MemberRewardServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemberRewardProvenanceTest {
    @Test
    void refundOfDebtOffsetLotReopensItsOriginalDebt() {
        var mapper = mock(MemberMapper.class);
        var redemption = mock(MemberPointRedemptionMapper.class);
        var settings = mock(MemberSettings.class);
        var accounts = mock(MemberAccountService.class);
        var growth = mock(MemberGrowthLedger.class);
        var account = new MemberAccount();
        account.setUserId(3L);
        var later = new MemberPointLot();
        later.setId(12L);
        later.setUserId(3L);
        later.setSourceKey("ORDER_POINTS:55");
        later.setIssuedUnits(400);
        later.setDebtOffsetUnits(400);
        later.setExpiresAt(LocalDateTime.parse("2026-11-07T20:00:00"));
        later.setStatus("EXHAUSTED");
        when(settings.enabled()).thenReturn(true);
        when(accounts.initializeAndLock(3L)).thenReturn(account);
        when(mapper.lot("ORDER_POINTS:55")).thenReturn(later);
        when(mapper.pointEntryId("REFUND_POINTS:21:55")).thenReturn(null);
        when(redemption.lotDebtOffsets(12L)).thenReturn(List.of(
                new MemberPointRedemptionMapper.DebtOffset(301L, 12L, 400, 0, 0)));
        doAnswer(call -> {
            ((MemberPointEntry) call.getArgument(0)).setId(100L);
            return null;
        }).when(mapper).insertPointEntry(any());
        var service = new MemberRewardServiceImpl(mapper, settings,
                Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.ofHours(8)), accounts,
                new MemberPointLedger(mapper, redemption), growth);

        service.refundConfirmed(3L, 7L, 21L,
                List.of(new RewardLine(55L, new BigDecimal("4.00"), new BigDecimal("4.00"))));

        assertEquals(400, account.getPointDebtUnits());
        assertEquals(0, later.getDebtOffsetUnits());
        assertEquals(400, later.getReversedUnits());
        verify(redemption).allocateLinked(100L, 12L, 301L, "REVERSE", 1, 400);
        verify(mapper).allocate(100L, 12L, "REVERSE", 400);
    }
}
