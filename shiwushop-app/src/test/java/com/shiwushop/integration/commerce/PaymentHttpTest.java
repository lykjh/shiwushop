package com.shiwushop.integration.commerce;

import com.shiwushop.support.*;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
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
