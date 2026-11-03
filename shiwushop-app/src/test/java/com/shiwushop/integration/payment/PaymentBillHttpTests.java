package com.shiwushop.integration.payment;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import com.shiwushop.payment.service.impl.BillFileDownloader;
import com.shiwushop.support.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","spring.datasource.hikari.maximum-pool-size=2"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentBillHttpTests {
 static final Path root;
 static { try { root=Files.createTempDirectory("shiwushop-bill-test-"); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("shop.payment.bill-root",()->root.toString()); }
 @LocalServerPort int port; @Autowired JdbcTemplate jdbc;
 @MockitoBean SdkAlipayGateway gateway; @MockitoBean BillFileDownloader downloader;
 TestAccounts accounts; TestAccounts.Account admin; HttpAccountClient client, user;
 ObjectMapper json=new ObjectMapper();
 @BeforeEach void setup() throws Exception {
  String base="http://127.0.0.1:"+port; accounts=new TestAccounts(jdbc);
  admin=accounts.create("ADMIN","Shop123!"); client=CommerceFixture.login(base,admin);
  user=CommerceFixture.login(base,accounts.create("USER","Shop123!"));
  when(gateway.channel()).thenReturn("ALIPAY"); when(gateway.appId()).thenReturn("test-app"); when(gateway.sellerId()).thenReturn("test-seller");
  when(gateway.billDownloadUrl(any())).thenReturn("https://dwbillcenter.alipay.com/file?signature=private");
  when(downloader.download(any())).thenReturn("PK-test-raw".getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
 @AfterEach void cleanup() throws Exception {
  if(admin!=null) jdbc.update("DELETE FROM payment_bills WHERE downloaded_by=?",admin.id());
  if(client!=null) client.close(); if(user!=null) user.close(); if(accounts!=null) accounts.close();
  try(var files=Files.list(root)) { for(Path file:files.toList()) Files.deleteIfExists(file); }
 }
 @AfterAll static void removeRoot() throws Exception { Files.deleteIfExists(root); }
 @Test void downloadedBillIsPrivateUnparsedAndIdempotent() throws Exception {
  String url="/admin/payment-bills"; var body=Map.<String,Object>of("billDate","2026-10-01");
  assertEquals(403,user.post(url,body).statusCode());
  assertEquals(403,client.post(url,body,false).statusCode());
  var first=client.post(url,body); assertEquals(200,first.statusCode(),first.body());
  var data=json.readTree(first.body()).path("data"); long id=data.path("id").asLong();
  assertEquals("DOWNLOADED_UNPARSED",data.path("status").asString());
  assertFalse(first.body().contains("storageKey")); assertFalse(first.body().contains("signature"));
  var again=client.post(url,body); assertEquals(200,again.statusCode(),again.body());
  assertEquals(data,json.readTree(again.body()).path("data"));
  assertEquals("PK-test-raw",client.get(url+"/"+id+"/file").body());
  assertEquals(403,user.get(url+"/"+id+"/file").statusCode());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_bills WHERE downloaded_by=?",Integer.class,admin.id()));
  try(var files=Files.list(root)) { assertEquals(1,files.count()); }
  when(downloader.download(any())).thenReturn("different".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  assertEquals(409,client.post(url,body).statusCode());
  try(var files=Files.list(root)) { assertEquals(1,files.count()); }
 }
 @Test void concurrentSameBillCreatesOneBatchAndOneFile() throws Exception {
  CountDownLatch entered=new CountDownLatch(2), release=new CountDownLatch(1);
  when(downloader.download(any())).thenAnswer(call -> {
   assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
   entered.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS));
   return "PK-concurrent".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  });
  try(var workers=Executors.newFixedThreadPool(2)) {
   var a=workers.submit(()->client.post("/admin/payment-bills",Map.of("billDate","2026-10-01")));
   var b=workers.submit(()->client.post("/admin/payment-bills",Map.of("billDate","2026-10-01")));
   try { assertTrue(entered.await(10,TimeUnit.SECONDS)); } finally { release.countDown(); }
   var left=a.get(15,TimeUnit.SECONDS); var right=b.get(15,TimeUnit.SECONDS);
   assertEquals(200,left.statusCode(),left.body()); assertEquals(200,right.statusCode(),right.body());
   assertEquals(json.readTree(left.body()).path("data").path("id").asLong(),json.readTree(right.body()).path("data").path("id").asLong());
  }
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_bills WHERE downloaded_by=?",Integer.class,admin.id()));
  try(var files=Files.list(root)) { assertEquals(1,files.count()); }
 }
 @Test void invalidDateAndMissingBillAreRejected() throws Exception {
  assertEquals(400,client.post("/admin/payment-bills",Map.of("billDate","2099-01-01")).statusCode());
  assertEquals(400,client.post("/admin/payment-bills",Map.of("billDate","invalid")).statusCode());
  assertEquals(404,client.get("/admin/payment-bills/999999999/file").statusCode());
  verifyNoInteractions(downloader);
 }
 @Test void failedChannelLeavesNoBatchOrFile() throws Exception {
  when(downloader.download(any())).thenThrow(new com.shiwushop.common.BusinessException(503,"账单暂不可取得"));
  assertEquals(503,client.post("/admin/payment-bills",Map.of("billDate","2026-10-01")).statusCode());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_bills WHERE downloaded_by=?",Integer.class,admin.id()));
  try(var files=Files.list(root)) { assertEquals(0,files.count()); }
 }
}
