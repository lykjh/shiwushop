package com.shiwushop.unit.marketing;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.dto.BudgetAdjustment;
import com.shiwushop.marketing.mapper.MarketingBudgetMapper;
import com.shiwushop.marketing.model.MarketingBudget;
import com.shiwushop.marketing.model.MarketingBudgetEntry;
import com.shiwushop.marketing.service.impl.MarketingBudgetServiceImpl;
import com.shiwushop.merchant.service.MerchantShopAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MarketingBudgetServiceTest {
    final MarketingBudgetMapper mapper = mock(MarketingBudgetMapper.class);
    final AccountSecurity security = mock(AccountSecurity.class);
    final MerchantShopAccess shops = mock(MerchantShopAccess.class);
    final MarketingBudgetServiceImpl service = new MarketingBudgetServiceImpl(mapper, security, shops,
            new MarketingSettings(true), new ObjectMapper(), Clock.systemUTC());
    final MarketingBudget budget = new MarketingBudget(3L, 2L, "PLATFORM", null, new BigDecimal("100.00"),
            new BigDecimal("80.00"), new BigDecimal("20.00"), null, null, 4L);

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01", "9999999999999999.99"})
    void invalidTotalsDoNotReachWrites(String delta) {
        when(mapper.lock(3L)).thenReturn(budget);
        var request = new BudgetAdjustment(new BigDecimal(delta), "调整", "E1");
        var error = assertThrows(BusinessException.class, () -> service.adjust(1L, null, 3L, "key", request));
        assertEquals(delta.equals("0") ? 400 : 409, error.getCode());
        verify(mapper, never()).updateAmount(anyLong(), any());
        verify(mapper, never()).insertAdjustment(any());
        verify(mapper, never()).audit(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void replayUsesPersistedRequestRatherThanDerivingItFromCurrentBudget() {
        when(mapper.lock(3L)).thenReturn(budget);
        var entry = new MarketingBudgetEntry();
        entry.setId(9L);
        entry.setBudgetDeltaAmount(new BigDecimal("1.00"));
        entry.setReason("调整");
        entry.setEvidenceReference("E1");
        when(mapper.operation(3L, "BUDGET_ADJUST:1:3:visible:~")).thenReturn(entry);
        var replay = service.adjust(1L, null, 3L, "visible:~", new BudgetAdjustment(BigDecimal.ONE, "调整", "E1"));
        assertEquals(9L, replay.budgetEntryId());
        assertEquals(budget.budgetAmount(), replay.budget().budgetAmount());
        verify(security).requireAdmin(1L);
        verify(mapper, never()).updateAmount(anyLong(), any());
    }

    @Test
    void disabledFeatureStillChecksCallerAndDoesNotReadBudget() {
        var disabled = new MarketingBudgetServiceImpl(mapper, security, shops, new MarketingSettings(false),
                new ObjectMapper(), Clock.systemUTC());
        assertEquals(404, assertThrows(BusinessException.class, () -> disabled.list(1L, null, 2L)).getCode());
        verify(security).requireAdmin(1L);
        verifyNoInteractions(mapper);
    }

    @Test
    void merchantWriteLocksShopBeforeBudgetAndCannotAccessPlatformResponsibility() {
        when(mapper.lock(3L)).thenReturn(budget);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.adjust(1L, 7L, 3L, "key",
                new BudgetAdjustment(BigDecimal.ONE, "调整", "E1"))).getCode());
        var order = inOrder(shops, mapper);
        order.verify(shops).requireOwnedForUpdate(1L, 7L, "店铺不存在");
        order.verify(mapper).lock(3L);
        verify(mapper, never()).updateAmount(anyLong(), any());
    }
}
