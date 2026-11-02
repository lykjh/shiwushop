package com.shiwushop.system.commerce;

import com.shiwushop.support.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 两个独立应用进程共享数据库；栅栏同时放行 HTTP 请求，核对全局库存与幂等结果。 */
class MultiInstanceCheckoutSystemIT {
    String peer() {
        String peer = System.getProperty("system.peer-url");
        assertNotNull(peer); assertTrue(peer.matches("http://127\\.0\\.0\\.1:[0-9]+"));
        assertNotEquals(SystemEnvironment.base(), peer); return peer;
    }
    @Test void differentBuyersOnTwoInstancesCannotOversellTheLastItem() throws Exception {
        var jdbc = SystemEnvironment.database();
        try (var first = new CommerceFixture(jdbc, SystemEnvironment.base());
             var second = new CommerceFixture(jdbc, peer()); var pool = Executors.newFixedThreadPool(2)) {
            jdbc.update("UPDATE skus SET stock=1 WHERE id=?", first.sku);
            var barrier = new CyclicBarrier(2);
            Map<String,Object> secondBody = new HashMap<>(second.direct("peer-last",1,"19.90")); secondBody.put("skuId",first.sku);
            var a = pool.submit(() -> {barrier.await(5,TimeUnit.SECONDS); return first.client.post("/checkouts/direct", first.direct("primary-last",1,"19.90")).statusCode();});
            var b = pool.submit(() -> {barrier.await(5,TimeUnit.SECONDS); return second.client.post("/checkouts/direct", secondBody).statusCode();});
            var statuses = new ArrayList<>(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))); Collections.sort(statuses);
            assertEquals(List.of(200,409),statuses);
            assertEquals(0,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,first.sku));
            assertEquals(1,jdbc.queryForObject("SELECT SUM(quantity) FROM order_items WHERE sku_id=?",Integer.class,first.sku));
        }
    }
    @Test void sameKeyOnTwoInstancesReturnsOneOrderAndDeductsStockOnce() throws Exception {
        var jdbc = SystemEnvironment.database(); var json = new ObjectMapper();
        try (var first = new CommerceFixture(jdbc, SystemEnvironment.base());
             var secondClient = CommerceFixture.login(peer(),first.buyer); var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2); var body = first.direct("two-instance-same-key",1,"19.90");
            var a = pool.submit(() -> {barrier.await(5,TimeUnit.SECONDS); return first.client.post("/checkouts/direct",body);});
            var b = pool.submit(() -> {barrier.await(5,TimeUnit.SECONDS); return secondClient.post("/checkouts/direct",body);});
            var primary = a.get(20,TimeUnit.SECONDS); var secondary = b.get(20,TimeUnit.SECONDS);
            assertEquals(200,primary.statusCode(),primary.body()); assertEquals(200,secondary.statusCode(),secondary.body());
            assertEquals(json.readTree(primary.body()),json.readTree(secondary.body()));
            assertEquals(19,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,first.sku));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?",Integer.class,first.buyer.id()));
        }
    }
}
