package com.shiwushop.support;

import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** 仅替代外部支付宝渠道，订单、事务、通知接口和持久化仍走真实实现。RSA 验签由 SDK 适配器测试独立覆盖。 */
public final class PaymentHttpFixture {
    private PaymentHttpFixture() {}
    public static void configure(SdkAlipayGateway gateway) {
        when(gateway.configured()).thenReturn(true); when(gateway.appId()).thenReturn("local-app");
        when(gateway.sellerId()).thenReturn("local-seller"); when(gateway.channel()).thenReturn("ALIPAY_SANDBOX");
        when(gateway.verify(anyMap())).thenAnswer(call -> "valid".equals(((Map<?,?>)call.getArgument(0)).get("sign")));
    }
    public static Map<String,String> notice(JdbcTemplate jdbc,long order) {
        String out=jdbc.queryForObject("SELECT out_trade_no FROM payment_attempts WHERE order_id=?",String.class,order);
        return new HashMap<>(Map.of("out_trade_no",out,"app_id","local-app","seller_id","local-seller",
                "total_amount","19.90","trade_status","TRADE_SUCCESS","trade_no","local-trade-"+order,"sign","valid"));
    }
    public static long paidOrder(CommerceFixture fixture,JdbcTemplate jdbc) throws Exception {
        long order=fixture.order("paid-order");
        var start=fixture.client.post("/orders/"+order+"/payment",Map.of()); assertEquals(200,start.statusCode(),start.body());
        var notice=fixture.client.form("/payments/alipay/notify",notice(jdbc,order));
        assertEquals(200,notice.statusCode(),notice.body()); assertEquals("success",notice.body()); return order;
    }
}
