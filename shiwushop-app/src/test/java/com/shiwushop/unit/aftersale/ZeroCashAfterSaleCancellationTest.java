package com.shiwushop.unit.aftersale;

import com.shiwushop.aftersale.entity.AfterSaleCase;
import com.shiwushop.aftersale.mapper.AfterSaleCaseMapper;
import com.shiwushop.aftersale.service.impl.AfterSaleServiceImpl;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.service.AfterSaleOrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZeroCashAfterSaleCancellationTest {
    @Mock
    AfterSaleCaseMapper cases;
    @Mock
    AfterSaleOrderService orders;
    @InjectMocks
    AfterSaleServiceImpl service;

    @Test
    void completedLegacyCancellationIsNotCreatedAgain() {
        Order order = new Order();
        order.setId(10L);
        when(cases.selectCount(any())).thenReturn(1L);

        service.cancelUnshippedZeroCash(order, List.of());

        verify(orders).shipment(10L);
        verify(cases, never()).insert(any(AfterSaleCase.class));
    }
}
