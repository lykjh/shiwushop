package com.shiwushop.unit.aftersale;

import com.shiwushop.aftersale.entity.AfterSaleCase;
import com.shiwushop.aftersale.entity.status.AfterSaleStatus;
import com.shiwushop.aftersale.entity.timeout.AfterSaleTimeoutTask;
import com.shiwushop.aftersale.mapper.AfterSaleCaseMapper;
import com.shiwushop.aftersale.mapper.timeout.AfterSaleTimeoutMapper;
import com.shiwushop.aftersale.service.impl.AfterSaleTimeoutServiceImpl;
import com.shiwushop.common.BusinessException;
import com.shiwushop.user.service.AccountAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AfterSaleTimeoutServiceImplTest {
    @Mock AfterSaleTimeoutMapper tasks;
    @Mock AccountAccess accounts;
    @Mock AfterSaleCaseMapper cases;
    @Mock PlatformTransactionManager transactions;
    @Mock Clock clock;
    @InjectMocks AfterSaleTimeoutServiceImpl service;

    @Test
    void anotherAdministratorCannotClaimOwnedTask() {
        var time = LocalDateTime.of(2026, 10, 1, 8, 0);
        var sale = new AfterSaleCase();
        sale.setStatus(AfterSaleStatus.REQUESTED);
        sale.setRequestedAt(time);
        var task = new AfterSaleTimeoutTask();
        task.setCaseId(7L);
        task.setStage("REVIEW");
        task.setBasisAt(time);
        task.setStatus("CLAIMED");
        task.setAssigneeId(11L);
        when(tasks.selectById(9L)).thenReturn(task);
        when(cases.selectForUpdate(7L)).thenReturn(sale);
        when(tasks.selectForUpdate(9L)).thenReturn(task);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        BusinessException error = assertThrows(BusinessException.class, () -> service.claim(12L, 9L));
        assertEquals(409, error.getCode());
        var locks = inOrder(cases, tasks);
        locks.verify(cases).selectForUpdate(7L);
        locks.verify(tasks).selectForUpdate(9L);
        verify(tasks, never()).claim(any(), any(), any());
    }

    @Test
    void listReturnsNoCursorWhenThereIsNoNextPage() {
        when(tasks.page(Long.MAX_VALUE, 21)).thenReturn(List.of());
        var result = service.list(12L, null, 20);
        assertEquals(List.of(), result.items());
        assertFalse(result.hasMore());
        assertEquals(null, result.nextBeforeId());
        verify(accounts).requireAdmin(12L);
    }
}
