package com.shiwushop.payment;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePrecreateModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeCancelRequest;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeCancelResponse;
import com.shiwushop.commerce.payment.AlipayGateway;
import com.shiwushop.commerce.payment.SdkAlipayGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneId;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SdkAlipayGatewayTests {
    @TempDir Path tempDir;

    @Test
    void appliesBoundedNetworkTimeoutsToTheSdkClient() throws Exception {
        Path keyFile = tempDir.resolve("private.pem");
        Files.writeString(keyFile, "test-key");
        SdkAlipayGateway gateway = new SdkAlipayGateway("app", "seller", "public-key",
                keyFile.toString(), "https://example.com/payments/alipay/notify",
                "https://openapi-sandbox.dl.alipaydev.com/gateway.do", Clock.systemUTC());
        ReflectionTestUtils.setField(gateway, "connectTimeoutMs", 2500);
        ReflectionTestUtils.setField(gateway, "readTimeoutMs", 7000);

        DefaultAlipayClient client = ReflectionTestUtils.invokeMethod(gateway, "client");

        assertEquals(2500, ReflectionTestUtils.getField(client, "connectTimeout"));
        assertEquals(7000, ReflectionTestUtils.getField(client, "readTimeout"));
    }

    @Test
    void selectsOfficialPaymentEnvironmentAndRejectsUnexpectedGateway() {
        String callback = "https://example.com/payments/alipay/notify";
        SdkAlipayGateway sandbox = new SdkAlipayGateway("app", "seller", "public-key",
                "private.pem", callback, "https://openapi-sandbox.dl.alipaydev.com/gateway.do",
                Clock.systemDefaultZone());
        SdkAlipayGateway production = new SdkAlipayGateway("app", "seller", "public-key",
                "private.pem", callback, "https://openapi.alipay.com/gateway.do",
                Clock.systemDefaultZone());
        assertEquals("ALIPAY_SANDBOX", sandbox.channel());
        assertEquals("ALIPAY", production.channel());
        assertThrows(IllegalArgumentException.class, () -> new SdkAlipayGateway("app", "seller",
                "public-key", "private.pem", callback, "https://example.com/gateway.do",
                Clock.systemDefaultZone()));
    }

    @Test
    void verifiesOnlyValidNotice() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        String privateKey = Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded());
        String publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        Path keyFile = tempDir.resolve("app-private.pem");
        Files.writeString(keyFile, privateKey);
        SdkAlipayGateway gateway = new SdkAlipayGateway("sandbox-app", "seller", publicKey,
                keyFile.toString(), "https://example.com/payments/alipay/notify",
                "https://openapi-sandbox.dl.alipaydev.com/gateway.do", Clock.systemDefaultZone());

        assertTrue(gateway.configured());
        Map<String, String> form = new HashMap<>();
        form.put("out_trade_no", "SHIWUSHOP123");
        form.put("total_amount", "20.00");
        form.put("trade_status", "TRADE_SUCCESS");
        form.put("sign_type", "RSA2");
        Map<String, String> unsigned = new HashMap<>(form);
        unsigned.remove("sign_type");
        form.put("sign", AlipaySignature.rsa256Sign(
                AlipaySignature.getSignCheckContentV1(unsigned), privateKey, "UTF-8"));
        assertTrue(gateway.verify(form));
        form.put("total_amount", "21.00");
        assertFalse(gateway.verify(form));
    }

    @Test
    void requestsSandboxQrForOrderAmountAndDeadline() throws Exception {
        Path keyFile = tempDir.resolve("private.pem");
        Files.writeString(keyFile, "test-key");
        SdkAlipayGateway gateway = new SdkAlipayGateway("sandbox-app", "seller", "public-key",
                keyFile.toString(), "https://example.com/payments/alipay/notify",
                "https://openapi-sandbox.dl.alipaydev.com/gateway.do",
                Clock.fixed(Instant.parse("2026-10-11T10:15:00Z"), ZoneId.of("Asia/Shanghai")));
        AlipayClient client = mock(AlipayClient.class);
        ReflectionTestUtils.setField(gateway, "client", client);
        AlipayTradePrecreateResponse response = new AlipayTradePrecreateResponse();
        response.setCode("10000");
        response.setQrCode("https://qr.alipay.com/test-payment");
        when(client.execute(any(AlipayTradePrecreateRequest.class))).thenReturn(response);

        LocalDateTime deadline = LocalDateTime.of(2026, 10, 11, 18, 30);
        AlipayGateway.QrCode qrCode = gateway.qrCode("SHIWUSHOP123", new BigDecimal("20.00"), deadline);
        assertEquals(response.getQrCode(), qrCode.value());
        assertEquals(LocalDateTime.of(2026, 10, 11, 18, 29), qrCode.expiresAt());
        ArgumentCaptor<AlipayTradePrecreateRequest> capture =
                ArgumentCaptor.forClass(AlipayTradePrecreateRequest.class);
        verify(client).execute(capture.capture());
        AlipayTradePrecreateRequest request = capture.getValue();
        AlipayTradePrecreateModel model = (AlipayTradePrecreateModel) request.getBizModel();
        assertEquals("https://example.com/payments/alipay/notify", request.getNotifyUrl());
        assertEquals("SHIWUSHOP123", model.getOutTradeNo());
        assertEquals("20.00", model.getTotalAmount());
        assertEquals("seller", model.getSellerId());
        assertEquals("2026-10-11 18:30:00", model.getTimeExpire());
        assertEquals("14m", model.getQrCodeTimeoutExpress());
    }

    @Test
    void distinguishesUnscannedPrecreateFromClosedTrade() throws Exception {
        Path keyFile = tempDir.resolve("private.pem");
        Files.writeString(keyFile, "test-key");
        SdkAlipayGateway gateway = new SdkAlipayGateway("sandbox-app", "seller", "public-key",
                keyFile.toString(), "https://example.com/payments/alipay/notify",
                "https://openapi-sandbox.dl.alipaydev.com/gateway.do", Clock.systemDefaultZone());
        AlipayClient client = mock(AlipayClient.class);
        ReflectionTestUtils.setField(gateway, "client", client);
        AlipayTradeCancelResponse response = new AlipayTradeCancelResponse();
        response.setCode("10000");
        response.setRetryFlag("N");
        when(client.execute(any(AlipayTradeCancelRequest.class))).thenReturn(response);
        assertEquals(AlipayGateway.CancelResult.NO_TRADE, gateway.cancel("SHIWUSHOP123"));

        response.setAction("refund");
        assertEquals(AlipayGateway.CancelResult.UNRESOLVED, gateway.cancel("SHIWUSHOP123"));
    }
}
