package com.shiwushop.unit.member;

import com.shiwushop.common.BusinessException;
import com.shiwushop.member.mapper.MemberMapper;
import com.shiwushop.member.mapper.MemberPointRedemptionMapper;
import com.shiwushop.member.model.MemberAccount;
import com.shiwushop.member.model.MemberPointEntry;
import com.shiwushop.member.model.MemberPointLot;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.member.service.impl.MemberPointLedger;
import com.shiwushop.member.service.impl.MemberPointRedemptionLedger;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemberPointRedemptionLedgerTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-05T20:00:00");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.ofHours(8));
    private final MemberMapper mapper = mock(MemberMapper.class);
    private final MemberPointRedemptionMapper redemption = mock(MemberPointRedemptionMapper.class);
    private final MemberAccountService accounts = mock(MemberAccountService.class);
    private final MemberPointLedger points = mock(MemberPointLedger.class);
    private final MemberPointRedemptionLedger ledger = new MemberPointRedemptionLedger(mapper, redemption,
            accounts, points, CLOCK);

    private MemberAccount account(long available, long debt) {
        var account = new MemberAccount();
        account.setUserId(3L);
        account.setAvailablePointUnits(available);
        account.setPointDebtUnits(debt);
        when(accounts.initializeAndLock(3L)).thenReturn(account);
        return account;
    }

    private static MemberPointLot lot(long id, long available, long spent, LocalDateTime expires) {
        var lot = new MemberPointLot();
        lot.setId(id);
        lot.setUserId(3L);
        lot.setAvailableUnits(available);
        lot.setSpentUnits(spent);
        lot.setExpiresAt(expires);
        lot.setStatus("ACTIVE");
        return lot;
    }

    private static MemberPointEntry entry(long id, long order, long user) {
        var entry = new MemberPointEntry();
        entry.setId(id);
        entry.setOrderId(order);
        entry.setUserId(user);
        entry.setEventKind("SPEND");
        return entry;
    }

    @Test
    void spendUsesEarliestUnexpiredLotsAndRejectsOutstandingDebt() {
        account(900, 0);
        var first = lot(11, 300, 0, NOW.plusDays(1));
        var second = lot(12, 600, 0, NOW.plusDays(2));
        when(mapper.availableLots(3L, NOW)).thenReturn(List.of(first, second));
        when(points.change(any(), eq("SPEND"), eq("POINTS:SPEND:7"), eq(7L), isNull(), eq(-500L), eq(0L)))
                .thenReturn(entry(99, 7, 3));

        assertEquals(99, ledger.spend(3, 7, 500));
        assertEquals(0, first.getAvailableUnits());
        assertEquals(300, first.getSpentUnits());
        assertEquals(400, second.getAvailableUnits());
        assertEquals(200, second.getSpentUnits());
        verify(mapper).allocate(99L, 11L, "SPEND", 300);
        verify(mapper).allocate(99L, 12L, "SPEND", 200);

        account(900, 1);
        assertEquals(409, assertThrows(BusinessException.class, () -> ledger.spend(3, 8, 100)).getCode());
        verify(points, never()).change(any(), eq("SPEND"), eq("POINTS:SPEND:8"), any(), any(), anyLong(), anyLong());
    }

    @Test
    void expiredOriginalLotStaysExpiredOnNormalReturn() {
        account(0, 0);
        when(mapper.pointEntry("POINTS:SPEND:7")).thenReturn(entry(99, 7, 3));
        when(redemption.spendAllocations("POINTS:SPEND:7"))
                .thenReturn(List.of(new MemberPointRedemptionMapper.SpendAllocation(91L, 11L, 500, 0)));
        var original = lot(11, 0, 500, NOW);
        when(redemption.lockLot(11)).thenReturn(original);
        doAnswer(call -> {
            ((MemberPointEntry) call.getArgument(1)).setId(100L);
            return null;
        }).when(points).record(any(), any());

        assertEquals(100, ledger.returnSpent(3, 7, null, 500, "cancel-7", false));
        assertEquals(0, original.getSpentUnits());
        assertEquals(500, original.getExpiredUnits());
        assertEquals("EXPIRED", original.getStatus());
        verify(redemption).allocateLinked(100, 11, 91, "RETURN", 1, 500);
        verify(mapper, never()).insertLot(any());
    }

    @Test
    void platformFailureCompensatesExpiredPointsForThirtyDaysAndOffsetsDebt() {
        account(0, 100);
        when(mapper.pointEntry("POINTS:SPEND:7")).thenReturn(entry(99, 7, 3));
        when(redemption.spendAllocations("POINTS:SPEND:7"))
                .thenReturn(List.of(new MemberPointRedemptionMapper.SpendAllocation(91L, 11L, 500, 0)));
        when(redemption.lockLot(11)).thenReturn(lot(11, 0, 500, NOW));
        doAnswer(call -> {
            ((MemberPointEntry) call.getArgument(1)).setId(100L);
            return null;
        }).when(points).record(any(), any());
        doAnswer(call -> {
            ((MemberPointLot) call.getArgument(0)).setId(12L);
            return null;
        }).when(mapper).insertLot(any());

        ledger.returnSpent(3, 7, 21L, 500, "return-21", true);

        var lot = org.mockito.ArgumentCaptor.forClass(MemberPointLot.class);
        verify(mapper).insertLot(lot.capture());
        assertEquals(500, lot.getValue().getIssuedUnits());
        assertEquals(400, lot.getValue().getAvailableUnits());
        assertEquals(100, lot.getValue().getDebtOffsetUnits());
        assertEquals(NOW.plusDays(30), lot.getValue().getExpiresAt());
        var pointEntry = org.mockito.ArgumentCaptor.forClass(MemberPointEntry.class);
        verify(points, times(2)).record(any(), pointEntry.capture());
        assertEquals(-100, pointEntry.getAllValues().get(1).getDebtDelta());
        assertEquals("PLATFORM_FAILURE_EXPIRED_POINTS", pointEntry.getAllValues().get(1).getReason());
        verify(mapper).allocate(100L, 12L, "COMPENSATE", 400);
        verify(points).allocateDebtOffset(eq(3L), eq(100L), eq(12L), eq(100L), anyLong());
    }

    @Test
    void sameReturnKeyReplaysButExcessReturnDoesNotWrite() {
        account(0, 0);
        var original = entry(99, 7, 3);
        var returned = entry(100, 7, 3);
        returned.setEventKind("RELEASE");
        returned.setReason("NORMAL_RETURN");
        when(mapper.pointEntry("POINTS:RETURN:7:cancel-7")).thenReturn(returned);
        when(redemption.returnedUnits(100)).thenReturn(500L);

        assertEquals(100, ledger.returnSpent(3, 7, null, 500, "cancel-7", false));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> ledger.returnSpent(3, 7, null, 400, "cancel-7", false)).getCode());
        verify(redemption, never()).lockLot(anyLong());

        when(mapper.pointEntry("POINTS:SPEND:7")).thenReturn(original);
        when(redemption.spendAllocations("POINTS:SPEND:7"))
                .thenReturn(List.of(new MemberPointRedemptionMapper.SpendAllocation(91L, 11L, 500, 400)));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> ledger.returnSpent(3, 7, null, 200, "cancel-8", false)).getCode());
        verify(redemption, never()).lockLot(anyLong());
        verify(points, never()).record(any(), any());
    }

    @Test
    void refundedSourceCannotBeCreditedAgainWithoutDebtAttribution() {
        account(0, 500);
        when(mapper.pointEntry("POINTS:SPEND:7")).thenReturn(entry(99, 7, 3));
        when(redemption.spendAllocations("POINTS:SPEND:7"))
                .thenReturn(List.of(new MemberPointRedemptionMapper.SpendAllocation(91L, 11L, 500, 0)));
        when(redemption.lockLot(11)).thenReturn(lot(11, 0, 0, NOW.plusDays(1)));

        assertEquals(409, assertThrows(BusinessException.class,
                () -> ledger.returnSpent(3, 7, null, 500, "cancel-7", false)).getCode());
        verify(points, never()).record(any(), any());
    }

    private MemberPointRedemptionLedger ledgerWithAccountPosting() {
        doAnswer(call -> {
            ((MemberPointEntry) call.getArgument(0)).setId(100L);
            return null;
        }).when(mapper).insertPointEntry(any());
        return new MemberPointRedemptionLedger(mapper, redemption, accounts, new MemberPointLedger(mapper, redemption), CLOCK);
    }

    @Test
    void fullSourceRefundThenRedemptionCancellationExtinguishesDebtWithoutRestoringRevokedPoints() {
        var account = account(0, 10_000);
        var source = lot(11, 0, 0, NOW.plusDays(1));
        source.setIssuedUnits(10_000);
        source.setReversedUnits(10_000);
        when(mapper.pointEntry("POINTS:SPEND:7")).thenReturn(entry(99, 7, 3));
        when(redemption.spendAllocations("POINTS:SPEND:7"))
                .thenReturn(List.of(new MemberPointRedemptionMapper.SpendAllocation(91L, 11L, 10_000, 0)));
        when(redemption.debtSources(91L)).thenReturn(List.of(
                new MemberPointRedemptionMapper.DebtSource(201L, 11L, 91L, 10_000, 0, 0)));
        when(redemption.lockLot(11)).thenReturn(source);
        var posting = ledgerWithAccountPosting();

        assertAll(
                () -> assertDoesNotThrow(() -> posting.returnSpent(3, 7, null, 10_000, "cancel-7", false)),
                () -> assertEquals(0, account.getAvailablePointUnits()),
                () -> assertEquals(0, account.getPointDebtUnits()),
                () -> assertEquals(0, source.getAvailableUnits()),
                () -> assertEquals(0, source.getSpentUnits()),
                () -> assertEquals(10_000, source.getReversedUnits()),
                () -> assertEquals(0, source.getDebtOffsetUnits()),
                () -> verify(redemption).allocateLinked(100, 11, 91, "RETURN", 1, 10_000),
                () -> verify(mapper).insertPointEntry(argThat(e -> e.getAvailableDelta() == 0
                        && e.getDebtDelta() == -10_000)));
    }

    @Test
    void laterEarnedPointsUsedForDebtReturnToTheirOwnLotWhenRedemptionIsCancelled() {
        var account = account(0, 6_000);
        var revoked = lot(11, 0, 0, NOW.plusDays(1));
        revoked.setIssuedUnits(10_000);
        revoked.setReversedUnits(10_000);
        var laterEarned = lot(12, 0, 0, NOW.plusDays(2));
        laterEarned.setIssuedUnits(4_000);
        laterEarned.setDebtOffsetUnits(4_000);
        when(mapper.pointEntry("POINTS:SPEND:7")).thenReturn(entry(99, 7, 3));
        when(redemption.spendAllocations("POINTS:SPEND:7"))
                .thenReturn(List.of(new MemberPointRedemptionMapper.SpendAllocation(91L, 11L, 10_000, 0)));
        when(redemption.debtSources(91L)).thenReturn(List.of(
                new MemberPointRedemptionMapper.DebtSource(201L, 11L, 91L, 10_000, 4_000, 0)));
        when(redemption.debtOffsets(201L)).thenReturn(List.of(
                new MemberPointRedemptionMapper.DebtOffset(301L, 12L, 4_000, 0, 0)));
        when(redemption.lockLot(11)).thenReturn(revoked);
        when(redemption.lockLot(12)).thenReturn(laterEarned);
        var posting = ledgerWithAccountPosting();

        assertAll(
                () -> assertDoesNotThrow(() -> posting.returnSpent(3, 7, null, 10_000, "cancel-7", false)),
                () -> assertEquals(4_000, account.getAvailablePointUnits()),
                () -> assertEquals(0, account.getPointDebtUnits()),
                () -> assertEquals(10_000, revoked.getReversedUnits()),
                () -> assertEquals(0, revoked.getAvailableUnits()),
                () -> assertEquals(4_000, laterEarned.getAvailableUnits()),
                () -> assertEquals(0, laterEarned.getDebtOffsetUnits()),
                () -> verify(redemption).allocateLinked(100, 11, 91, "RETURN", 1, 10_000),
                () -> verify(mapper).updateLot(laterEarned),
                () -> verify(mapper).insertPointEntry(argThat(e -> e.getAvailableDelta() == 4_000
                        && e.getDebtDelta() == -6_000)));
    }

    @Test
    void partialSourceRefundAndPartialRedemptionCancellationKeepOriginalLotCategories() {
        var account = account(0, 1_000);
        var source = lot(11, 0, 5_000, NOW.plusDays(1));
        source.setIssuedUnits(10_000);
        source.setReversedUnits(5_000);
        when(mapper.pointEntry("POINTS:SPEND:7")).thenReturn(entry(99, 7, 3));
        when(redemption.spendAllocations("POINTS:SPEND:7"))
                .thenReturn(List.of(new MemberPointRedemptionMapper.SpendAllocation(91L, 11L, 6_000, 0)));
        when(redemption.debtSources(91L)).thenReturn(List.of(
                new MemberPointRedemptionMapper.DebtSource(201L, 11L, 91L, 1_000, 0, 0)));
        when(redemption.lockLot(11)).thenReturn(source);
        var posting = ledgerWithAccountPosting();

        assertAll(
                () -> assertDoesNotThrow(() -> posting.returnSpent(3, 7, null, 3_000, "cancel-7-part", false)),
                () -> assertEquals(2_000, account.getAvailablePointUnits()),
                () -> assertEquals(0, account.getPointDebtUnits()),
                () -> assertEquals(2_000, source.getAvailableUnits()),
                () -> assertEquals(3_000, source.getSpentUnits()),
                () -> assertEquals(5_000, source.getReversedUnits()),
                () -> assertEquals(0, source.getDebtOffsetUnits()),
                () -> verify(redemption).allocateLinked(100, 11, 91, "RETURN", 1, 3_000),
                () -> verify(mapper).insertPointEntry(argThat(e -> e.getAvailableDelta() == 2_000
                        && e.getDebtDelta() == -1_000)));
    }

    @Test
    void oneEventSplitsDebtCreationAndLaterOffsetBySourceId() {
        var posting = new MemberPointLedger(mapper, redemption);
        when(redemption.lotSpends(11L)).thenReturn(List.of(
                new MemberPointRedemptionMapper.SpendAllocation(91L, 11L, 1_000, 0),
                new MemberPointRedemptionMapper.SpendAllocation(92L, 11L, 2_000, 0)));
        posting.allocateDebtCreate(100, 11, 3_000, 3_000);
        verify(redemption).allocateLinked(100, 11, 91, "DEBT_CREATE", 1, 1_000);
        verify(redemption).allocateLinked(100, 11, 92, "DEBT_CREATE", 2, 2_000);

        when(redemption.accountDebts(3L)).thenReturn(List.of(
                new MemberPointRedemptionMapper.DebtSource(201L, 11L, 91L, 1_000, 0, 0),
                new MemberPointRedemptionMapper.DebtSource(202L, 11L, 92L, 2_000, 0, 0)));
        posting.allocateDebtOffset(3, 101, 12, 3_000, 3_000);
        verify(redemption).allocateLinked(101, 12, 201, "DEBT_OFFSET", 1, 1_000);
        verify(redemption).allocateLinked(101, 12, 202, "DEBT_OFFSET", 2, 2_000);

        when(redemption.lotDebtOffsets(12L)).thenReturn(List.of(
                new MemberPointRedemptionMapper.DebtOffset(301L, 12L, 1_000, 0, 0),
                new MemberPointRedemptionMapper.DebtOffset(302L, 12L, 2_000, 0, 0)));
        posting.reverseDebtOffsets(102, 12, 3_000, 3_000);
        verify(redemption).allocateLinked(102, 12, 301, "REVERSE", 1, 1_000);
        verify(redemption).allocateLinked(102, 12, 302, "REVERSE", 2, 2_000);
    }

    @Test
    void partialCancellationClearsOnlyItsOwnDebtThenRestoresLaterLot() {
        var account = account(0, 600);
        var revoked = lot(11, 0, 0, NOW.plusDays(1));
        revoked.setReversedUnits(1_000);
        var later = lot(12, 0, 0, NOW.plusDays(2));
        later.setDebtOffsetUnits(400);
        when(mapper.pointEntry("POINTS:SPEND:7")).thenReturn(entry(99, 7, 3));
        when(redemption.spendAllocations("POINTS:SPEND:7")).thenReturn(
                List.of(new MemberPointRedemptionMapper.SpendAllocation(91L, 11L, 1_000, 0)),
                List.of(new MemberPointRedemptionMapper.SpendAllocation(91L, 11L, 1_000, 600)));
        when(redemption.debtSources(91L)).thenReturn(
                List.of(new MemberPointRedemptionMapper.DebtSource(201L, 11L, 91L, 1_000, 400, 0)),
                List.of(new MemberPointRedemptionMapper.DebtSource(201L, 11L, 91L, 1_000, 400, 600)));
        when(redemption.debtOffsets(201L)).thenReturn(
                List.of(new MemberPointRedemptionMapper.DebtOffset(301L, 12L, 400, 0, 0)));
        when(redemption.lockLot(11L)).thenReturn(revoked);
        when(redemption.lockLot(12L)).thenReturn(later);
        var ids = new java.util.concurrent.atomic.AtomicLong(100);
        doAnswer(call -> {
            ((MemberPointEntry) call.getArgument(0)).setId(ids.getAndIncrement());
            return null;
        }).when(mapper).insertPointEntry(any());
        var posting = new MemberPointRedemptionLedger(mapper, redemption, accounts,
                new MemberPointLedger(mapper, redemption), CLOCK);

        assertEquals(100, posting.returnSpent(3, 7, null, 600, "first", false));
        assertEquals(0, account.getPointDebtUnits());
        assertEquals(0, account.getAvailablePointUnits());
        assertEquals(400, later.getDebtOffsetUnits());
        assertEquals(101, posting.returnSpent(3, 7, null, 400, "second", false));
        assertEquals(0, account.getPointDebtUnits());
        assertEquals(400, account.getAvailablePointUnits());
        assertEquals(0, later.getDebtOffsetUnits());
        verify(redemption).allocateLinked(100, 11, 201, "DEBT_REPAY", 1, 600);
        verify(redemption).allocateLinked(101, 12, 301, "DEBT_REPAY", 1, 400);
    }

    @Test
    void expiredLaterOffsetReturnsAsExpiredWithoutNewCompensation() {
        var account = account(0, 600);
        var revoked = lot(11, 0, 0, NOW.plusDays(1));
        revoked.setReversedUnits(1_000);
        var later = lot(12, 0, 0, NOW);
        later.setDebtOffsetUnits(400);
        when(mapper.pointEntry("POINTS:SPEND:7")).thenReturn(entry(99, 7, 3));
        when(redemption.spendAllocations("POINTS:SPEND:7")).thenReturn(
                List.of(new MemberPointRedemptionMapper.SpendAllocation(91L, 11L, 1_000, 0)));
        when(redemption.debtSources(91L)).thenReturn(List.of(
                new MemberPointRedemptionMapper.DebtSource(201L, 11L, 91L, 1_000, 400, 0)));
        when(redemption.debtOffsets(201L)).thenReturn(List.of(
                new MemberPointRedemptionMapper.DebtOffset(301L, 12L, 400, 0, 0)));
        when(redemption.lockLot(11L)).thenReturn(revoked);
        when(redemption.lockLot(12L)).thenReturn(later);

        ledgerWithAccountPosting().returnSpent(3, 7, null, 1_000, "expired-later", false);

        assertEquals(0, account.getAvailablePointUnits());
        assertEquals(0, account.getPointDebtUnits());
        assertEquals(400, later.getExpiredUnits());
        assertEquals(0, later.getDebtOffsetUnits());
        assertEquals(NOW, later.getExpiresAt());
        verify(mapper, never()).insertLot(any());
    }

    @Test
    void twoSpendSourcesInOneLotGetDistinctReturnOrdinals() {
        var account = account(0, 0);
        var source = lot(11, 0, 200, NOW.plusDays(1));
        when(mapper.pointEntry("POINTS:SPEND:7")).thenReturn(entry(99, 7, 3));
        when(redemption.spendAllocations("POINTS:SPEND:7")).thenReturn(List.of(
                new MemberPointRedemptionMapper.SpendAllocation(91L, 11L, 100, 0),
                new MemberPointRedemptionMapper.SpendAllocation(92L, 11L, 100, 0)));
        when(redemption.lockLot(11L)).thenReturn(source);

        ledgerWithAccountPosting().returnSpent(3, 7, null, 200, "both", false);

        assertEquals(200, account.getAvailablePointUnits());
        assertEquals(200, source.getAvailableUnits());
        assertEquals(0, source.getSpentUnits());
        verify(redemption).allocateLinked(100, 11, 91, "RETURN", 1, 100);
        verify(redemption).allocateLinked(100, 11, 92, "RETURN", 2, 100);
    }

    @Test
    void refundOfLaterOffsetLotReopensOriginalDebtWithoutRestoringRevokedPoints() {
        var account = account(0, 1_000);
        var revoked = lot(11, 0, 0, NOW.plusDays(1));
        revoked.setReversedUnits(1_000);
        var later = lot(12, 0, 0, NOW.plusDays(2));
        later.setReversedUnits(400);
        when(mapper.pointEntry("POINTS:SPEND:7")).thenReturn(entry(99, 7, 3));
        when(redemption.spendAllocations("POINTS:SPEND:7")).thenReturn(List.of(
                new MemberPointRedemptionMapper.SpendAllocation(91L, 11L, 1_000, 0)));
        when(redemption.debtSources(91L)).thenReturn(List.of(
                new MemberPointRedemptionMapper.DebtSource(201L, 11L, 91L, 1_000, 0, 0)));
        when(redemption.debtOffsets(201L)).thenReturn(List.of(
                new MemberPointRedemptionMapper.DebtOffset(301L, 12L, 400, 0, 400)));
        when(redemption.lockLot(11L)).thenReturn(revoked);

        ledgerWithAccountPosting().returnSpent(3, 7, null, 1_000, "after-later-refund", false);

        assertEquals(0, account.getPointDebtUnits());
        assertEquals(0, account.getAvailablePointUnits());
        assertEquals(0, later.getAvailableUnits());
        assertEquals(400, later.getReversedUnits());
        verify(redemption, never()).lockLot(12L);
        verify(redemption).allocateLinked(100, 11, 201, "DEBT_REPAY", 1, 1_000);
    }

    @Test
    void historicalDebtWithoutSpendSourceRequiresReviewInsteadOfGuessing() {
        var posting = new MemberPointLedger(mapper, redemption);
        when(redemption.accountDebts(3L)).thenReturn(List.of(
                new MemberPointRedemptionMapper.DebtSource(201L, 11L, null, 100, 0, 0)));

        var failure = assertThrows(BusinessException.class,
                () -> posting.allocateDebtOffset(3, 101, 12, 100, 100));
        assertEquals(409, failure.getCode());
        assertEquals("POINT_PROVENANCE_REQUIRES_REVIEW",
                ((java.util.Map<?, ?>) failure.getData()).get("reason"));
        verify(redemption, never()).allocateLinked(anyLong(), anyLong(), anyLong(), anyString(), anyInt(), anyLong());
    }
}
