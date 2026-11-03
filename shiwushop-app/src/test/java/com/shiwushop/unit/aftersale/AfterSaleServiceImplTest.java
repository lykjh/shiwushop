package com.shiwushop.unit.aftersale;

import com.shiwushop.aftersale.entity.AfterSaleCase;
import com.shiwushop.aftersale.entity.status.AfterSaleStatus;
import com.shiwushop.aftersale.mapper.AfterSaleAuditMapper;
import com.shiwushop.aftersale.mapper.AfterSaleCaseMapper;
import com.shiwushop.aftersale.mapper.AfterSaleItemMapper;
import com.shiwushop.aftersale.mapper.ReturnDestinationMapper;
import com.shiwushop.aftersale.mapper.refund.RefundAttemptMapper;
import com.shiwushop.aftersale.mapper.shipment.ReturnShipmentMapper;
import com.shiwushop.aftersale.service.impl.AfterSaleServiceImpl;
import com.shiwushop.common.BusinessException;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.merchant.service.ShopReturnProfileService;
import com.shiwushop.payment.service.RefundGateway;
import com.shiwushop.product.service.ProductStockService;
import com.shiwushop.product.service.ReviewService;
import com.shiwushop.product.service.TradingProductService;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.service.AfterSaleOrderService;
import com.shiwushop.user.service.AccountAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AfterSaleServiceImplTest {
    @Mock AfterSaleCaseMapper cases;
    @Mock AfterSaleItemMapper items;
    @Mock ReturnDestinationMapper destinations;
    @Mock ShopReturnProfileService returnProfiles;
    @Mock ReturnShipmentMapper returns;
    @Mock RefundAttemptMapper refunds;
    @Mock AfterSaleAuditMapper audit;
    @Mock MerchantShopAccess merchantAccess;
    @Mock AccountAccess accountAccess;
    @Mock RefundGateway gateway;
    @Mock PlatformTransactionManager transactions;
    @Mock Clock clock;
    @Mock AfterSaleOrderService orders;
    @Mock TradingProductService products;
    @Mock ProductStockService stock;
    @Mock ReviewService reviews;
    @InjectMocks AfterSaleServiceImpl service;

    @Test
    void withdrawalLocksOrderBeforeCaseAndRejectsRefunding() {
        var sale = new AfterSaleCase();
        sale.setOrderId(5L);
        sale.setStatus(AfterSaleStatus.REFUNDING);
        var order = new Order();
        order.setUserId(7L);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(cases.selectById(9L)).thenReturn(sale);
        when(orders.find(5L)).thenReturn(order);
        when(cases.selectForUpdate(9L)).thenReturn(sale);
        BusinessException error = assertThrows(BusinessException.class, () -> service.withdraw(7L, 9L));
        assertEquals(409, error.getCode());
        var locks = inOrder(orders, cases);
        locks.verify(orders).lock(5L);
        locks.verify(cases).selectForUpdate(9L);
        verify(cases, never()).withdraw(any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }
}
