package com.shiwushop.unit.settlement;

import com.shiwushop.trade.settlement.AfterSaleSettlementFacts;
import com.shiwushop.marketing.service.MarketingOrderSettlement;
import com.shiwushop.marketing.service.MarketingSettlementFacts;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.entity.status.OrderStatus;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.trade.settlement.impl.ManualSettlementServiceImpl;
import com.shiwushop.trade.settlement.mapper.ManualSettlementMapper;
import com.shiwushop.trade.vo.order.OrderFulfillmentVO;
import com.shiwushop.user.service.AccountAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManualSettlementServiceTest {
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
    private final ManualSettlementMapper settlements = mock(ManualSettlementMapper.class);
    private final OrderMapper orders = mock(OrderMapper.class);
    private final ShopService shops = mock(ShopService.class);
    private final MarketingSettlementFacts marketing = mock(MarketingSettlementFacts.class);
    private final AfterSaleSettlementFacts afterSales = mock(AfterSaleSettlementFacts.class);
    private OrderFulfillmentVO progress;
    private final ManualSettlementServiceImpl service = new ManualSettlementServiceImpl(settlements,
            orders, shops, mock(AccountAccess.class), mock(MarketingOrderSettlement.class),
            marketing, afterSales, NOW);

    @BeforeEach
    void setup() {
        when(shops.findById(7L)).thenReturn(mock(Shop.class));
        when(settlements.candidateIds(7, null, 21)).thenReturn(List.of(11L));
        when(afterSales.cases(11)).thenReturn(List.of());
        when(settlements.itemFacts(11)).thenReturn(new ManualSettlementMapper.ItemFacts(
                1, 0, 0, 0, money("80")));
        progress = new OrderFulfillmentVO();
        progress.setTotalQuantity(1);
        progress.setShippedQuantity(1);
        progress.setConfirmedQuantity(1);
        when(orders.fulfillmentProgress(11L)).thenReturn(progress);
    }

    @Test
    void sevenFullDaysAndFrozenPlatformShareDetermineCashPayable() {
        Order order = order("CASH", "80.00", "100.00", "20.00", "12.00");
        when(orders.selectById(11L)).thenReturn(order);
        when(settlements.itemFacts(11)).thenReturn(new ManualSettlementMapper.ItemFacts(1, 0, 0, 0, money("80")));
        when(settlements.successfulPayment(11)).thenReturn(new ManualSettlementMapper.PaymentFact(31, money("80")));
        when(marketing.snapshot(11)).thenReturn(new MarketingSettlementFacts.Snapshot(
                new MarketingSettlementFacts.Totals(money("20"), money("12"), money("8"),
                        money("0"), 0, 0, 0, 0), List.of()));

        order.setFullyReceivedAt(LocalDateTime.of(2026, 10, 31, 0, 0, 1));
        var early = service.candidates(1, 7, null, 20).items().getFirst();
        assertEquals("INELIGIBLE", early.state());
        assertNull(early.payableAmount());

        order.setFullyReceivedAt(LocalDateTime.of(2026, 10, 31, 0, 0));
        var payable = service.candidates(1, 7, null, 20).items().getFirst();
        assertEquals("PAYABLE", payable.state());
        assertEquals(money("92"), payable.payableAmount());

        var partial = new OrderFulfillmentVO();
        partial.setTotalQuantity(2);
        partial.setShippedQuantity(1);
        partial.setConfirmedQuantity(1);
        when(orders.fulfillmentProgress(11L)).thenReturn(partial);
        assertEquals("INELIGIBLE", service.candidates(1, 7, null, 20).items().getFirst().state());
        when(orders.fulfillmentProgress(11L)).thenReturn(progress);

        when(afterSales.cases(11)).thenReturn(List.of(new AfterSaleSettlementFacts.Case(
                41, "REFUNDED", money("20"), money("20"), 0, 1)));
        when(marketing.snapshot(11)).thenReturn(new MarketingSettlementFacts.Snapshot(
                new MarketingSettlementFacts.Totals(money("20"), money("12"), money("8"),
                        money("0"), 0, 0, 0, 0),
                List.of(new MarketingSettlementFacts.Reversal(41, money("20"), money("3"),
                        money("0"), 1, 1, 0))));
        assertEquals(money("69"), service.candidates(1, 7, null, 20).items().getFirst().payableAmount());
    }

    @Test
    void zeroCashNeedsPlatformSubsidyAndPointsNeedFrozenCost() {
        Order order = order("ZERO_CASH", "0", "10", "10", "10");
        when(orders.selectById(11L)).thenReturn(order);
        when(settlements.itemFacts(11)).thenReturn(new ManualSettlementMapper.ItemFacts(1, 0, 0, 0, money("0")));
        when(marketing.snapshot(11)).thenReturn(new MarketingSettlementFacts.Snapshot(
                new MarketingSettlementFacts.Totals(money("10"), money("10"), money("0"),
                        money("0"), 0, 0, 0, 0), List.of()));
        assertEquals(money("10"), service.candidates(1, 7, null, 20).items().getFirst().payableAmount());

        order.setSettlementKind("POINTS");
        order.setBusinessType("POINTS");
        order.setOriginalAmount(money("0"));
        order.setDiscountAmount(money("0"));
        order.setPlatformSubsidyAmount(money("0"));
        when(settlements.itemFacts(11)).thenReturn(new ManualSettlementMapper.ItemFacts(1, 0, 1, 0, money("0")));
        when(marketing.snapshot(11)).thenReturn(new MarketingSettlementFacts.Snapshot(
                new MarketingSettlementFacts.Totals(money("0"), money("0"), money("0"),
                        money("0"), 0, 0, 0, 0), List.of()));
        var missing = service.candidates(1, 7, null, 20).items().getFirst();
        assertEquals("REVIEW_REQUIRED", missing.state());
        assertEquals(List.of("FROZEN_SUPPLY_COST_MISSING"), missing.missingFacts());
    }

    @Test
    void pendingAfterSaleAndUnresolvedRefundNeverBecomePayable() {
        when(orders.selectById(11L)).thenReturn(order("CASH", "80", "100", "20", "12"));
        when(afterSales.cases(11)).thenReturn(List.of(new AfterSaleSettlementFacts.Case(
                41, "REFUNDING", money("20"), money("0"), 1, 1)));
        var candidate = service.candidates(1, 7, null, 20).items().getFirst();
        assertEquals("INELIGIBLE", candidate.state());
        assertEquals(List.of("REFUND_RESULT_UNRESOLVED", "AFTER_SALE_IN_PROGRESS"), candidate.missingFacts());
    }

    @Test
    void fullyRefundedOrderNeedsNoPaymentAndExcessRefundRequiresReview() {
        when(orders.selectById(11L)).thenReturn(order("CASH", "10", "10", "0", "0"));
        when(settlements.successfulPayment(11)).thenReturn(new ManualSettlementMapper.PaymentFact(31, money("10")));
        when(settlements.itemFacts(11)).thenReturn(new ManualSettlementMapper.ItemFacts(1, 0, 0, 0, money("10")));
        when(marketing.snapshot(11)).thenReturn(new MarketingSettlementFacts.Snapshot(
                new MarketingSettlementFacts.Totals(money("0"), money("0"), money("0"),
                        money("0"), 0, 0, 0, 0), List.of(new MarketingSettlementFacts.Reversal(
                        41, money("10"), money("0"), money("0"), 1, 1, 0))));
        when(afterSales.cases(11)).thenReturn(List.of(new AfterSaleSettlementFacts.Case(
                41, "REFUNDED", money("10"), money("10"), 0, 1)));
        assertEquals("NO_PAYMENT", service.candidates(1, 7, null, 20).items().getFirst().state());
        when(afterSales.cases(11)).thenReturn(List.of(new AfterSaleSettlementFacts.Case(
                41, "REFUNDED", money("11"), money("11"), 0, 1)));
        assertEquals("REVIEW_REQUIRED", service.candidates(1, 7, null, 20).items().getFirst().state());
    }

    private static Order order(String kind, String total, String original, String discount, String subsidy) {
        var order = new Order();
        order.setId(11L);
        order.setShopId(7L);
        order.setShopOwnerUserIdAtOrder(9L);
        order.setStatus(OrderStatus.PAID);
        order.setSettlementKind(kind);
        order.setBusinessType("NORMAL");
        order.setTotalAmount(money(total));
        order.setOriginalAmount(money(original));
        order.setDiscountAmount(money(discount));
        order.setPlatformSubsidyAmount(money(subsidy));
        order.setPricingVersion(1);
        order.setFullyReceivedAt(LocalDateTime.of(2026, 10, 31, 0, 0));
        return order;
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount).setScale(2);
    }
}
