package com.shiwushop.integration.commerce;

import com.shiwushop.support.*;
import com.shiwushop.payment.service.*;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.autoconfigure.exclude=",
        "spring.datasource.hikari.maximum-pool-size=2","shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AfterSaleHttpTest {
    @LocalServerPort int port; @Autowired JdbcTemplate jdbc; @MockitoBean SdkAlipayGateway gateway;
    CommerceFixture fixture; long order,item; final ObjectMapper json=new ObjectMapper();
    @BeforeEach void prepare() throws Exception {
        PaymentHttpFixture.configure(gateway); fixture=new CommerceFixture(jdbc,"http://127.0.0.1:"+port);
        order=PaymentHttpFixture.paidOrder(fixture,jdbc);
        item=jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?",Long.class,order);
    }
    @AfterEach void cleanup(){if(fixture!=null)fixture.close();}
    Map<String,Object> body(String type,int quantity){return Map.of("type",type,"reason","尺寸不合适","items",List.of(Map.of("orderItemId",item,"quantity",quantity)));}
    long apply(String type) throws Exception {
        var response=fixture.client.request("POST","/orders/"+order+"/after-sales",body(type,1),true,Map.of("Idempotency-Key","sale-request"));
        assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body()).path("data").path("id").asLong();
    }
    @Test void refundOnlyIsIdempotentAndRestocksExactlyOnce() throws Exception {
        long sale=apply("REFUND_ONLY"); assertEquals(sale,apply("REFUND_ONLY"));
        String merchant="/merchant/shops/"+fixture.catalog.shop+"/after-sales/"+sale;
        assertEquals(200,fixture.merchantClient.post(merchant+"/review",Map.of("approved",true)).statusCode());
        when(gateway.refund(anyString(),anyString(),anyString(),any())).thenAnswer(call->new RefundGateway.Result(true,
                call.getArgument(0),call.getArgument(1),call.getArgument(2),new BigDecimal("19.90")));
        for(int n=0;n<2;n++){var response=fixture.merchantClient.post(merchant+"/refund",Map.of());assertEquals(200,response.statusCode(),response.body());
            assertEquals("REFUNDED",json.readTree(response.body()).path("data").path("status").asString());}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM refund_attempts WHERE case_id=?",Integer.class,sale));
        assertEquals(20,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku));
    }
    @Test void overQuantityAndReturnBeforeShipmentAreRejected() throws Exception {
        for(var body:List.of(body("REFUND_ONLY",2),body("RETURN_REFUND",1))){
            var response=fixture.client.request("POST","/orders/"+order+"/after-sales",body,true,Map.of("Idempotency-Key","invalid-sale"));
            assertEquals(409,response.statusCode(),response.body());}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_cases WHERE order_id=?",Integer.class,order));
    }
}
