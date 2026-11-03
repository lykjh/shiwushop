package com.shiwushop.integration.commerce;

import com.shiwushop.support.*;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import com.shiwushop.payment.service.AlipayGateway;
import java.math.BigDecimal;
import java.util.concurrent.*;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.autoconfigure.exclude=",
        "spring.datasource.hikari.maximum-pool-size=2","shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentHttpTest {
    @LocalServerPort int port; @Autowired JdbcTemplate jdbc; @MockitoBean SdkAlipayGateway gateway;
    CommerceFixture fixture;
    @BeforeEach void prepare() throws Exception { PaymentHttpFixture.configure(gateway); fixture=new CommerceFixture(jdbc,"http://127.0.0.1:"+port); }
    @AfterEach void cleanup(){if(fixture!=null)fixture.close();}
    @Test void paidCallbackWinsAgainstBlockedCancellationOverHttp() throws Exception {
        long order=fixture.order("cancel-race");
        assertEquals(200,fixture.client.post("/orders/"+order+"/payment",Map.of()).statusCode());
        String trade=jdbc.queryForObject("SELECT out_trade_no FROM payment_attempts WHERE order_id=?",String.class,order);
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        when(gateway.query(trade)).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5,TimeUnit.SECONDS));
            return new AlipayGateway.TradeResult("WAIT_BUYER_PAY",trade,null,new BigDecimal("19.90"));
        });
        when(gateway.close(trade)).thenReturn(true);
        var pending=CompletableFuture.supplyAsync(() -> {
            try { return fixture.client.post("/orders/"+order+"/cancel",Map.of()).statusCode(); }
            catch (Exception e) { throw new CompletionException(e); }
        });
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals(200,fixture.client.form("/payments/alipay/notify",PaymentHttpFixture.notice(jdbc,order)).statusCode());
        } finally { release.countDown(); }
        assertEquals(409,pending.get(5,TimeUnit.SECONDS));
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?) AND reason='ORDER_CANCEL_RETURN'",Integer.class,order));
    }
    @Test void pendingCancellationIsVisibleOnOrderDetailOverHttp() throws Exception {
        long order=fixture.order("cancel-pending");
        assertEquals(200,fixture.client.post("/orders/"+order+"/payment",Map.of()).statusCode());
        String trade=jdbc.queryForObject("SELECT out_trade_no FROM payment_attempts WHERE order_id=?",String.class,order);
        when(gateway.query(trade)).thenReturn(new AlipayGateway.TradeResult("NOT_FOUND",trade,null,null));
        var pending=fixture.client.post("/orders/"+order+"/cancel",Map.of());
        assertEquals(202,pending.statusCode(),pending.body());
        var detail=fixture.client.get("/orders/"+order);
        assertEquals(200,detail.statusCode(),detail.body());
        assertTrue(detail.body().contains("\"status\":\"UNPAID\""));
        assertTrue(detail.body().contains("\"cancelPending\":true"));
        assertTrue(detail.body().contains("\"items\":"));
    }
    @Test void cancellationUsesRealHttpAndReturnsStockOnlyOnce() throws Exception {
        long order=fixture.order("cancel-order");
        assertEquals(404,fixture.merchantClient.post("/orders/"+order+"/cancel",Map.of()).statusCode());
        for(int i=0;i<2;i++) {
            var response=fixture.client.post("/orders/"+order+"/cancel",Map.of());
            assertEquals(200,response.statusCode(),response.body());
            assertTrue(response.body().contains("CANCELLED"));
        }
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?) AND reason='ORDER_CANCEL_RETURN'",Integer.class,order));
        assertEquals(20,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku));
    }
    @Test void successfulNoticeCanBeRepeatedWithoutDoublePaymentOrSales() throws Exception {
        long order=PaymentHttpFixture.paidOrder(fixture,jdbc);
        var repeated=fixture.client.form("/payments/alipay/notify",PaymentHttpFixture.notice(jdbc,order));
        assertEquals(200,repeated.statusCode(),repeated.body());
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE order_id=? AND status='SUCCESS'",Integer.class,order));
        assertEquals(1L,jdbc.queryForObject("SELECT sold_count FROM product_sales_stats WHERE product_id=?",Long.class,fixture.product));
    }
    @ParameterizedTest @ValueSource(strings={"sign","app_id","seller_id","total_amount"})
    void invalidNoticeDoesNotMarkOrderPaid(String field) throws Exception {
        long order=fixture.order("invalid-notice");
        assertEquals(200,fixture.client.post("/orders/"+order+"/payment",Map.of()).statusCode());
        var body=PaymentHttpFixture.notice(jdbc,order); body.put(field,field.equals("total_amount")?"0.01":"incorrect");
        var response=fixture.client.form("/payments/alipay/notify",body);
        assertEquals(400,response.statusCode(),response.body()); assertEquals("failure",response.body());
        assertEquals("UNPAID",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order));
    }
    @Test void merchantCannotReadOrPayTheBuyersOrder() throws Exception {
        long order=fixture.order("owner-check");
        assertEquals(404,fixture.merchantClient.get("/orders/"+order).statusCode());
        assertEquals(404,fixture.merchantClient.post("/orders/"+order+"/payment",Map.of()).statusCode());
    }
}
