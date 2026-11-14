package com.shiwushop.unit.marketing;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.mapper.MarketingActivityMapper;
import com.shiwushop.marketing.mapper.MarketingBudgetHoldMapper;
import com.shiwushop.marketing.model.MarketingBudget;
import com.shiwushop.marketing.model.MarketingBudgetHold;
import com.shiwushop.marketing.service.impl.CouponBudgetCommitments;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CouponBudgetCommitmentsTest {
    final MarketingActivityMapper activities = mock(MarketingActivityMapper.class);
    final MarketingBudgetHoldMapper holds = mock(MarketingBudgetHoldMapper.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.ofHours(8));
    final CouponBudgetCommitments service = new CouponBudgetCommitments(activities, holds, clock);
    final LocalDateTime expiry = LocalDateTime.of(2026, 12, 1, 0, 0);

    MarketingBudget budget(long id, Long shop, Integer bp, String amount) {
        return new MarketingBudget(id, 1L, shop == null ? "PLATFORM" : "MERCHANT", shop, new BigDecimal(amount),
                BigDecimal.ZERO, BigDecimal.ZERO, bp, shop == null ? null : LocalDateTime.now(clock), 0L);
    }

    @Test
    void crossShopCouponHoldsMaximumPlatformShareAndEveryCandidateMerchantShare() {
        when(activities.lockBudgets(1L)).thenReturn(
                List.of(budget(1, null, null, "9"), budget(2, 20L, 5000, "5"), budget(3, 30L, 9000, "1")));
        service.reserve(1L, 100L, new BigDecimal("10.00"), expiry);
        var capture = ArgumentCaptor.forClass(MarketingBudgetHold.class);
        verify(holds, times(3)).insert(capture.capture());
        var values = capture.getAllValues();
        assertEquals(List.of(new BigDecimal("9.00"), new BigDecimal("5.00"), new BigDecimal("1.00")),
                values.stream().map(MarketingBudgetHold::getHeldAmount).toList());
        assertTrue(values.stream().allMatch(v -> v.getSourceId() == 100L && v.getExpiresAt().equals(expiry)));
    }

    @Test
    void oneCentRoundingRemainderBelongsToMerchant() {
        when(activities.lockBudgets(1L)).thenReturn(List.of(budget(1, null, null, "0"), budget(2, 20L, 9999, "1")));
        service.reserve(1L, 100L, new BigDecimal("0.01"), expiry);
        var capture = ArgumentCaptor.forClass(MarketingBudgetHold.class);
        verify(holds).insert(capture.capture());
        assertEquals(2L, capture.getValue().getBudgetId());
        assertEquals(new BigDecimal("0.01"), capture.getValue().getHeldAmount());
    }

    @Test
    void insufficientCandidateBudgetFailsBeforeAnyHoldOrLedgerIsWritten() {
        when(activities.lockBudgets(1L)).thenReturn(
                List.of(budget(1, null, null, "100"), budget(2, 20L, 5000, "4.99")));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.reserve(1L, 100L, new BigDecimal("10.00"), expiry)).getCode());
        verifyNoInteractions(holds);
    }
}
