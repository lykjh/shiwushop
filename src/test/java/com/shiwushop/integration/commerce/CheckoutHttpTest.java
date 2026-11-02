package com.shiwushop.integration.commerce;

import com.shiwushop.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=4",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CheckoutHttpTest {
    @LocalServerPort int port; @Autowired JdbcTemplate jdbc;
    CommerceFixture fixture; final ObjectMapper json=new ObjectMapper();
    @BeforeEach void prepare() throws Exception { fixture=new CommerceFixture(jdbc,"http://127.0.0.1:"+port); }
    @AfterEach void cleanup() { if(fixture!=null) fixture.close(); }
    @Test void cartCheckoutWritesSnapshotsDeletesCartAndReusesIdempotencyResult() throws Exception {
        var added=fixture.client.post("/cart/items",Map.of("skuId",fixture.sku,"quantity",2));
        assertEquals(200,added.statusCode(),added.body()); long item=json.readTree(added.body()).path("data").path("id").asLong();
        var body=Map.<String,Object>of("cartItemIds",List.of(item),"addressId",fixture.address,"confirmedTotal","39.80","idempotencyKey","cart-checkout");
        var first=fixture.client.post("/checkouts",body); assertEquals(200,first.statusCode(),first.body());
        var retry=fixture.client.post("/checkouts",body); assertEquals(200,retry.statusCode(),retry.body());
        assertEquals(json.readTree(first.body()),json.readTree(retry.body()));
        assertEquals(18,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE user_id=?",Integer.class,fixture.buyer.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?",Integer.class,fixture.buyer.id()));
    }
    @ParameterizedTest @ValueSource(strings={"0.01","19.89","19.91"})
    void priceMismatchDoesNotDeductStockOrKeepCheckoutRequest(String price) throws Exception {
        var response=fixture.client.post("/checkouts/direct",fixture.direct("wrong-price",1,price));
        assertEquals(409,response.statusCode(),response.body());
        assertEquals("PRICE_CHANGED",json.readTree(response.body()).path("data").path("reason").asString());
        assertEquals(20,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM checkout_requests WHERE user_id=?",Integer.class,fixture.buyer.id()));
    }
    @Test void insufficientStockRollsBackTheEntireRequest() throws Exception {
        var response=fixture.client.post("/checkouts/direct",fixture.direct("too-many",21,"417.90"));
        assertEquals(409,response.statusCode(),response.body());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?",Integer.class,fixture.buyer.id()));
        assertEquals(20,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku));
    }
    @Test void changedPayloadUnderSameIdempotencyKeyIsRejected() throws Exception {
        fixture.order("same-key");
        var response=fixture.client.post("/checkouts/direct",fixture.direct("same-key",2,"39.80"));
        assertEquals(409,response.statusCode(),response.body());
        assertEquals(19,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku));
    }
    @Test void concurrentRequestsForLastItemHaveOneWinner() throws Exception {
        jdbc.update("UPDATE skus SET stock=1 WHERE id=?",fixture.sku);
        try(var second=CommerceFixture.login("http://127.0.0.1:"+port,fixture.buyer); var pool=Executors.newFixedThreadPool(2)) {
            CyclicBarrier barrier=new CyclicBarrier(2);
            var a=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return fixture.client.post("/checkouts/direct",fixture.direct("last-a",1,"19.90")).statusCode();});
            var b=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return second.post("/checkouts/direct",fixture.direct("last-b",1,"19.90")).statusCode();});
            var result=new ArrayList<>(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))); Collections.sort(result);
            assertEquals(List.of(200,409),result); assertEquals(0,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku));
        }
    }
}
