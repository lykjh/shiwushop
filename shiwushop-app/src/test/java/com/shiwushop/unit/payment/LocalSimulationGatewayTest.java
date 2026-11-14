package com.shiwushop.unit.payment;

import com.shiwushop.common.BusinessException;
import com.shiwushop.trade.payment.LocalSimulationGateway;
import com.shiwushop.trade.entity.payment.PaymentAttempt;
import com.shiwushop.trade.entity.status.PaymentAttemptStatus;
import com.shiwushop.trade.mapper.payment.PaymentAttemptMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.shiwushop.payment.service.RefundGateway;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import java.time.Clock;
import com.shiwushop.payment.service.AlipayGateway;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalSimulationGatewayTest {
    @Test
    void paymentAndRefundDefaultToAlipayAndRequireExplicitSimulationOptIn() {
        var runner = new ApplicationContextRunner()
                .withBean(PaymentAttemptMapper.class, () -> mock(PaymentAttemptMapper.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("shop.alipay.app-id=", "shop.alipay.seller-id=",
                        "shop.alipay.public-key=", "shop.alipay.private-key-path=",
                        "shop.alipay.notify-url=", "shop.alipay.gateway-url=https://openapi.alipay.com/gateway.do")
                .withUserConfiguration(SdkAlipayGateway.class, LocalSimulationGateway.class);
        runner.run(context -> {
            assertFalse(context.containsBean("localSimulationGateway"));
            assertInstanceOf(SdkAlipayGateway.class, context.getBean(AlipayGateway.class));
            assertInstanceOf(SdkAlipayGateway.class, context.getBean(RefundGateway.class));
        });
        runner.withPropertyValues("shop.payment.simulation-enabled=true").run(context -> {
            assertEquals("LOCAL_SIMULATION", context.getBean(AlipayGateway.class).channel());
            assertInstanceOf(LocalSimulationGateway.class, context.getBean(RefundGateway.class));
        });
    }

    @Test
    void pendingQueryCannotPayAndSignedCallbackIsNeverAccepted() {
        var payments = mock(PaymentAttemptMapper.class);
        var jdbc = mock(JdbcTemplate.class);
        var gateway = new LocalSimulationGateway(payments, jdbc);
        PaymentAttempt payment = new PaymentAttempt();
        payment.setChannel("LOCAL_SIMULATION");
        payment.setAppId("LOCAL_SIMULATION");
        payment.setSellerId("LOCAL_SIMULATION");
        payment.setStatus(PaymentAttemptStatus.PENDING);
        payment.setAmount(new BigDecimal("19.90"));
        when(payments.selectByOutTradeNo("LOCAL_SIMULATION123")).thenReturn(payment);
        assertTrue(gateway.configured());
        assertEquals("WAIT_BUYER_PAY", gateway.query("LOCAL_SIMULATION123").status());
        assertFalse(gateway.verify(Map.of("trade_status", "TRADE_SUCCESS", "sign", "anything")));
        verify(payments).selectByOutTradeNo("LOCAL_SIMULATION123");
        verifyNoMoreInteractions(payments);
        verifyNoInteractions(jdbc);
    }

    @Test
    void formalChannelCannotBeQueriedClosedOrRefundedThroughSimulation() {
        var payments = mock(PaymentAttemptMapper.class);
        var jdbc = mock(JdbcTemplate.class);
        var gateway = new LocalSimulationGateway(payments, jdbc);
        PaymentAttempt real = new PaymentAttempt();
        real.setChannel("ALIPAY");
        when(payments.selectByOutTradeNo("real")).thenReturn(real);
        assertThrows(BusinessException.class, () -> gateway.query("real"));
        assertThrows(BusinessException.class, () -> gateway.close("real"));
        assertThrows(BusinessException.class, () -> gateway.refund("real", "trade", "refund", BigDecimal.ONE));
        verifyNoInteractions(jdbc);
    }
}
