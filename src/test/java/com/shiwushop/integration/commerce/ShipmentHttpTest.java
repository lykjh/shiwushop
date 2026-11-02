package com.shiwushop.integration.commerce;

import com.shiwushop.support.*;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.autoconfigure.exclude=",
        "spring.datasource.hikari.maximum-pool-size=2","shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ShipmentHttpTest {
    @LocalServerPort int port;@Autowired JdbcTemplate jdbc;@MockitoBean SdkAlipayGateway gateway;
    CommerceFixture f;final ObjectMapper json=new ObjectMapper();
    @BeforeEach void prepare() throws Exception{PaymentHttpFixture.configure(gateway);f=new CommerceFixture(jdbc,"http://127.0.0.1:"+port);}
    @AfterEach void cleanup(){if(f!=null)f.close();}
    @Test void paidOrderShipsIdempotentlyAndBuyerConfirmsOnce() throws Exception {
        long order=PaymentHttpFixture.paidOrder(f,jdbc);String path="/merchant/shops/"+f.catalog.shop+"/orders/"+order+"/shipment";
        Map<String,Object> body=Map.of("carrierName","顺丰速运","trackingNo","SF202611010001");
        var first=f.merchantClient.post(path,body);assertEquals(200,first.statusCode(),first.body());
        var retry=f.merchantClient.post(path,body);assertEquals(200,retry.statusCode());
        var firstData=json.readTree(first.body()).path("data");var retryData=json.readTree(retry.body()).path("data");
        for(String field:List.of("orderId","status","carrierName","trackingNo"))assertEquals(firstData.path(field),retryData.path(field));
        // 当前 DATETIME 按秒落库；验证同一发货事实，不把响应纳秒与落库舍入差异当作重复发货。
        var firstTime=java.time.LocalDateTime.parse(firstData.path("shippedAt").asString());
        var storedTime=java.time.LocalDateTime.parse(retryData.path("shippedAt").asString());
        assertTrue(java.time.Duration.between(firstTime,storedTime).abs().toMillis()<=1000);
        assertEquals(409,f.merchantClient.post(path,Map.of("carrierName","顺丰速运","trackingNo","SF202611010002")).statusCode());
        for(int n=0;n<2;n++){var response=f.client.post("/orders/"+order+"/shipment/confirm",Map.of());assertEquals(200,response.statusCode(),response.body());}
        assertEquals("CONFIRMED",jdbc.queryForObject("SELECT status FROM shipments WHERE order_id=?",String.class,order));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id=?",Integer.class,order));
    }
    @Test void unpaidOrderCannotBeShipped() throws Exception {
        long order=f.order("unpaid-shipment");var response=f.merchantClient.post("/merchant/shops/"+f.catalog.shop+"/orders/"+order+"/shipment",
                Map.of("carrierName","顺丰速运","trackingNo","SF202611010003"));assertEquals(409,response.statusCode(),response.body());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id=?",Integer.class,order));
    }
}
