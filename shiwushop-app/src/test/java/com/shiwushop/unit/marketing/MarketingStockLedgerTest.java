package com.shiwushop.unit.marketing;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.mapper.MarketingStockMapper;
import com.shiwushop.marketing.service.impl.MarketingStockLedger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketingStockLedgerTest {
    private final MarketingStockMapper mapper = mock(MarketingStockMapper.class);
    private final MarketingStockLedger ledger = new MarketingStockLedger(mapper);

    @Test
    void reserveConfirmAndReleaseMoveOneUnitWithoutTouchingNormalStock() {
        when(mapper.lockPool(7)).thenReturn(new MarketingStockMapper.Pool(3L, 7L));
        when(mapper.change(anyLong(), anyInt(), anyInt(), anyInt())).thenReturn(1);
        ledger.reserve(7, 9, 1, "hold:9");
        ledger.confirm(7, 9, 1, "paid:9");
        ledger.release(7, 9, 1, "cancel:9");
        verify(mapper).change(3, -1, 1, 0);
        verify(mapper).change(3, 0, -1, 1);
        verify(mapper).change(3, 1, -1, 0);
        verify(mapper).record(3, "hold:9", "RESERVE", -1, 1, 0, 9);
    }

    @Test
    void replayAndMismatchNeverMutatePoolAgain() {
        when(mapper.lockPool(7)).thenReturn(new MarketingStockMapper.Pool(3L, 7L));
        when(mapper.entry("hold:9"))
                .thenReturn(new MarketingStockMapper.Entry(3L, 9L, "RESERVE", -1, 1, 0));
        ledger.reserve(7, 9, 1, "hold:9");
        assertEquals(409, assertThrows(BusinessException.class,
                () -> ledger.reserve(7, 9, 2, "hold:9")).getCode());
        verify(mapper, never()).change(anyLong(), anyInt(), anyInt(), anyInt());
        verify(mapper, never()).record(anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void lastUnitConflictDoesNotWriteStockEntry() {
        when(mapper.lockPool(7)).thenReturn(new MarketingStockMapper.Pool(3L, 7L));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> ledger.reserve(7, 9, 1, "hold:9")).getCode());
        verify(mapper, never()).record(anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyLong());
    }
}
