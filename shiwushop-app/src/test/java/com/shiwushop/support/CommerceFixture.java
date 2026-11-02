package com.shiwushop.support;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** 为接口测试准备目录和两个真实登录会话；下单、地址和购物车通过 HTTP 创建。 */
public final class CommerceFixture implements AutoCloseable {
    public final CatalogFixture catalog;
    public final TestAccounts.Account buyer;
    public final HttpAccountClient client, merchantClient;
    public final long product, sku, address;
    private final TestAccounts accounts;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    public CommerceFixture(JdbcTemplate jdbc, String base) throws Exception {
        this.jdbc=jdbc; catalog=new CatalogFixture(jdbc); accounts=new TestAccounts(jdbc);
        buyer=accounts.create("USER","Shop123!");
        product=catalog.product("洁丽雅纯棉毛巾","ON_SALE","19.90",20); sku=catalog.sku(product);
        client=login(base,buyer); merchantClient=login(base,catalog.merchant);
        var response=client.post("/addresses",Map.of("consignee","张明","phone","13812345678","province","江苏省","city","南京市","district","鼓楼区","detail","中山北路28号"));
        if(response.statusCode()!=200) throw new IllegalStateException(response.body());
        address=json.readTree(response.body()).path("data").path("id").asLong();
    }
    public static HttpAccountClient login(String base, TestAccounts.Account account) throws Exception {
        var client=new HttpAccountClient(base); client.csrf();
        var response=client.post("/users/login",Map.of("username",account.username(),"password",account.password()));
        if(response.statusCode()!=200) { client.close(); throw new IllegalStateException(response.body()); }
        client.csrf(); return client;
    }
    public Map<String,Object> direct(String key,int quantity,String total) {
        return Map.of("skuId",sku,"quantity",quantity,"addressId",address,"confirmedTotal",total,"idempotencyKey",key);
    }
    public long order(String key) throws Exception {
        var response=client.post("/checkouts/direct",direct(key,1,"19.90"));
        if(response.statusCode()!=200) throw new IllegalStateException(response.body());
        return json.readTree(response.body()).path("data").path("orders").get(0).path("id").asLong();
    }
    @Override public void close() {
        client.close(); merchantClient.close();
        for(long order:jdbc.queryForList("SELECT id FROM orders WHERE user_id=?",Long.class,buyer.id())) {
            for(long review:jdbc.queryForList("SELECT r.id FROM product_reviews r JOIN order_items i ON i.id=r.order_item_id WHERE i.order_id=?",Long.class,order)) {
                jdbc.update("DELETE FROM product_review_moderation_audit WHERE review_id=?",review);
                jdbc.update("DELETE FROM product_review_reports WHERE review_id=?",review);
                jdbc.update("DELETE FROM product_review_images WHERE review_id=?",review);
                jdbc.update("DELETE FROM product_reviews WHERE id=?",review);
            }
            for(long sale:jdbc.queryForList("SELECT id FROM after_sale_cases WHERE order_id=?",Long.class,order)) {
                jdbc.update("DELETE FROM refund_attempts WHERE case_id=?",sale);
                jdbc.update("DELETE FROM return_shipments WHERE case_id=?",sale);
                jdbc.update("DELETE FROM after_sale_items WHERE case_id=?",sale);
                jdbc.update("DELETE FROM after_sale_cases WHERE id=?",sale);
            }
            for(long shipment:jdbc.queryForList("SELECT id FROM shipments WHERE order_id=?",Long.class,order)) {
                jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id=?",shipment);
            }
            jdbc.update("DELETE FROM shipments WHERE order_id=?",order);
            jdbc.update("DELETE FROM payment_reconciliation_issues WHERE order_id=?",order);
            jdbc.update("DELETE FROM payment_attempts WHERE order_id=?",order);
            jdbc.update("DELETE m FROM inventory_movements m JOIN order_items i ON i.id=m.order_item_id WHERE i.order_id=?",order);
            jdbc.update("DELETE FROM order_items WHERE order_id=?",order);
            jdbc.update("DELETE FROM checkout_request_orders WHERE order_id=?",order);
            jdbc.update("DELETE FROM orders WHERE id=?",order);
        }
        jdbc.update("DELETE FROM checkout_requests WHERE user_id=?",buyer.id());
        jdbc.update("DELETE FROM cart_items WHERE user_id=?",buyer.id());
        jdbc.update("DELETE FROM addresses WHERE user_id=?",buyer.id());
        jdbc.update("DELETE FROM audit_events WHERE actor_user_id IN (?,?)",buyer.id(),catalog.merchant.id());
        jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='PRODUCT' AND aggregate_id=?",product);
        catalog.close(); accounts.close();
    }
}
