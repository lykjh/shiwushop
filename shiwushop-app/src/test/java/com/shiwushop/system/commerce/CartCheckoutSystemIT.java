package com.shiwushop.system.commerce;

import com.shiwushop.support.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 独立进程中的跨接口旅程：登录、地址、购物车、结算、买家订单和商家订单。 */
class CartCheckoutSystemIT {
    @Test void buyerAndMerchantSeeTheSameOrderAndStockChangesOnce() throws Exception {
        String base=SystemEnvironment.base();var jdbc=SystemEnvironment.database();var json=new ObjectMapper();
        try(var f=new CommerceFixture(jdbc,base)) {
            var added=f.client.post("/cart/items",Map.of("skuId",f.sku,"quantity",1));assertEquals(200,added.statusCode(),added.body());
            long item=json.readTree(added.body()).path("data").path("id").asLong();
            assertEquals(200,f.client.request("PUT","/cart/items/"+item,Map.of("quantity",2)).statusCode());
            var body=Map.<String,Object>of("cartItemIds",List.of(item),"addressId",f.address,"confirmedTotal","39.80","idempotencyKey","system-cart");
            var checked=f.client.post("/checkouts",body);assertEquals(200,checked.statusCode(),checked.body());
            long order=json.readTree(checked.body()).path("data").path("orders").get(0).path("id").asLong();
            var own=f.client.get("/orders/"+order);assertEquals(200,own.statusCode());
            assertEquals(2,json.readTree(own.body()).path("data").path("items").get(0).path("quantity").asInt());
            var merchant=f.merchantClient.get("/merchant/shops/"+f.catalog.shop+"/orders/"+order);
            assertEquals(200,merchant.statusCode(),merchant.body());
            assertEquals(order,json.readTree(merchant.body()).path("data").path("id").asLong());
            assertEquals(200,f.client.post("/checkouts",body).statusCode());
            assertEquals(18,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,f.sku));
        }
    }
}
