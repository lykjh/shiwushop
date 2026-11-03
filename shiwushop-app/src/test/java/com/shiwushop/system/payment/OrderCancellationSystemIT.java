package com.shiwushop.system.payment;
import com.shiwushop.support.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
/** 独立进程订单取消：真实下单、库存扣减和幂等返库。 */
class OrderCancellationSystemIT {
 @Test void checkoutThenCancelReturnsStockExactlyOnce() throws Exception {
  var jdbc=SystemEnvironment.database();
  try(var fixture=new CommerceFixture(jdbc,SystemEnvironment.base())) {
   long order=fixture.order("system-cancel");
   assertEquals(200,fixture.client.post("/checkouts/direct",fixture.direct("system-cancel",1,"19.90")).statusCode());
   assertEquals(19,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku));
   for(int i=0;i<2;i++) {
    var result=fixture.client.post("/orders/"+order+"/cancel",Map.of());
    assertEquals(200,result.statusCode(),result.body()); assertTrue(result.body().contains("CANCELLED"));
   }
   assertEquals(200,fixture.client.post("/checkouts/direct",fixture.direct("system-cancel",1,"19.90")).statusCode());
   assertEquals(20,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku));
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?) AND reason='ORDER_CANCEL_RETURN'",Integer.class,order));
   assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order));
  }
 }
}
