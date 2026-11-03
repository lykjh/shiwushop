package com.shiwushop.unit.payment;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayDataDataserviceBillDownloadurlQueryModel;
import com.alipay.api.request.AlipayDataDataserviceBillDownloadurlQueryRequest;
import com.alipay.api.response.AlipayDataDataserviceBillDownloadurlQueryResponse;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
class SdkBillGatewayTests {
 @TempDir Path root;
 @Test void sendsTradeDateAndSecureTrue() throws Exception {
  Path key=Files.writeString(root.resolve("key.pem"),"test");
  var gateway=new SdkAlipayGateway("app","seller","public",key.toString(),"https://example.com/notify","https://openapi.alipay.com/gateway.do",Clock.systemUTC());
  AlipayClient client=mock(AlipayClient.class); ReflectionTestUtils.setField(gateway,"client",client);
  var response=new AlipayDataDataserviceBillDownloadurlQueryResponse(); response.setCode("10000"); response.setBillDownloadUrl("https://dwbillcenter.alipay.com/file");
  when(client.execute(any(AlipayDataDataserviceBillDownloadurlQueryRequest.class))).thenReturn(response);
  assertEquals("https://dwbillcenter.alipay.com/file",gateway.billDownloadUrl(LocalDate.of(2026,10,1)));
  var captor=ArgumentCaptor.forClass(AlipayDataDataserviceBillDownloadurlQueryRequest.class);
  verify(client).execute(captor.capture());
  var model=(AlipayDataDataserviceBillDownloadurlQueryModel)captor.getValue().getBizModel();
  assertEquals("trade",model.getBillType()); assertEquals("2026-10-01",model.getBillDate()); assertEquals("true",model.getSecure());
 }
}
