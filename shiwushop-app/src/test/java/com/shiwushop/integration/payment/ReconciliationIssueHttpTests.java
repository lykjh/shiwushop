package com.shiwushop.integration.payment;
import com.shiwushop.support.*;
import com.shiwushop.payment.mapper.ReconciliationIssueMapper;
import com.shiwushop.payment.mapper.ReconciliationAuditMapper;
import com.shiwushop.payment.entity.ReconciliationAudit;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","spring.datasource.hikari.maximum-pool-size=2"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ReconciliationIssueHttpTests {
 @LocalServerPort int port; @Autowired JdbcTemplate jdbc;
 @MockitoSpyBean ReconciliationIssueMapper issueMapper;
 @MockitoSpyBean ReconciliationAuditMapper auditMapper;
 CommerceFixture fixture; TestAccounts admins; HttpAccountClient first, second; long issue;
 final ObjectMapper json=new ObjectMapper();
 @BeforeEach void prepare() throws Exception {
  String base="http://127.0.0.1:"+port;
  fixture=new CommerceFixture(jdbc,base); admins=new TestAccounts(jdbc);
  first=CommerceFixture.login(base,admins.create("ADMIN","Shop123!"));
  second=CommerceFixture.login(base,admins.create("ADMIN","Shop123!"));
  long order=fixture.order("reconciliation");
  jdbc.update("INSERT INTO payment_reconciliation_issues(order_id,issue_type,status,summary,detected_at) VALUES (?,'AMOUNT_MISMATCH','OPEN','金额待核对',NOW())",order);
  issue=jdbc.queryForObject("SELECT id FROM payment_reconciliation_issues WHERE order_id=?",Long.class,order);
 }
 @AfterEach void cleanup() {
  if(issue>0) { jdbc.update("DELETE FROM payment_reconciliation_issue_audit WHERE issue_id=?",issue); jdbc.update("DELETE FROM payment_reconciliation_evidence WHERE issue_id=?",issue); jdbc.update("DELETE FROM payment_reconciliation_issues WHERE id=?",issue); }
  if(first!=null) first.close(); if(second!=null) second.close(); if(admins!=null) admins.close(); if(fixture!=null) fixture.close();
 }
 @Test void claimEvidenceResolveAndIdempotentRetry() throws Exception {
  String path="/admin/payment-reconciliation/issues/"+issue;
  assertEquals(403,fixture.client.get(path).statusCode());
  assertEquals(200,first.get("/admin/payment-reconciliation/issues?status=OPEN&size=20").statusCode());
  try (var anonymous=new HttpAccountClient("http://127.0.0.1:"+port)) { assertEquals(401,anonymous.get(path).statusCode()); }

  assertEquals(403,first.post(path+"/claim",Map.of(),false).statusCode());
  var claimed=first.post(path+"/claim",Map.of());
  var claimedAgain=first.post(path+"/claim",Map.of());
  assertEquals(200,claimed.statusCode()); assertEquals(200,claimedAgain.statusCode());
  assertEquals(json.readTree(claimed.body()).path("data"),json.readTree(claimedAgain.body()).path("data"));
  assertEquals(409,second.post(path+"/claim",Map.of()).statusCode());
  var proof=first.post(path+"/evidence",Map.of("billEvidence","  账单无该笔  ","localEvidence","本地流水已核实","channelQueryEvidence","渠道查询未找到"));
  assertEquals(200,proof.statusCode(),proof.body());
  long proofId=json.readTree(proof.body()).path("data").path("id").asLong();
  var close=Map.<String,Object>of("evidenceId",proofId,"conclusionType","NO_ACTION","conclusion","无需处理");
  var resolved=first.post(path+"/resolve",close);
  var resolvedAgain=first.post(path+"/resolve",close);
  assertEquals(200,resolved.statusCode()); assertEquals(200,resolvedAgain.statusCode());
  assertEquals(json.readTree(resolved.body()).path("data"),json.readTree(resolvedAgain.body()).path("data"));
  assertEquals(409,first.post(path+"/resolve",Map.of("evidenceId",proofId,"conclusionType","LOCAL_ONLY","conclusion","变化")).statusCode());
  assertEquals(409,first.post(path+"/evidence",Map.of("billEvidence","a","localEvidence","b","channelQueryEvidence","c")).statusCode());
  assertEquals("RESOLVED",jdbc.queryForObject("SELECT status FROM payment_reconciliation_issues WHERE id=?",String.class,issue));
  assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_issue_audit WHERE issue_id=?",Integer.class,issue));
  assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_issue_audit WHERE issue_id=? AND request_id IS NOT NULL",Integer.class,issue));
  assertEquals("账单无该笔",jdbc.queryForObject("SELECT bill_evidence FROM payment_reconciliation_evidence WHERE id=?",String.class,proofId));
  assertTrue(first.get(path).body().contains("channelQueryEvidence"));
 }
 @Test void allFiveConclusionsArePersistedWithoutMoneyOrOrderChanges() throws Exception {
  long order=jdbc.queryForObject("SELECT order_id FROM payment_reconciliation_issues WHERE id=?",Long.class,issue);
  int stockBefore=jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku);
  for(String type : List.of("CHANNEL_ONLY","LOCAL_ONLY","AMOUNT_MISMATCH","REFUND_STATUS_MISMATCH","NO_ACTION")) {
   String path="/admin/payment-reconciliation/issues/"+issue;
   assertEquals(200,first.post(path+"/claim",Map.of()).statusCode());
   var evidenceResponse=first.post(path+"/evidence",Map.of("billEvidence","账单已核实","localEvidence","本地未找到，查询单号无记录","channelQueryEvidence","渠道查询已核实"));
   assertEquals(200,evidenceResponse.statusCode(),evidenceResponse.body());
   long evidenceId=json.readTree(evidenceResponse.body()).path("data").path("id").asLong();
   var close=first.post(path+"/resolve",Map.of("evidenceId",evidenceId,"conclusionType",type,"conclusion","核实完毕"));
   assertEquals(200,close.statusCode(),close.body());
   assertEquals(type,jdbc.queryForObject("SELECT conclusion_type FROM payment_reconciliation_issues WHERE id=?",String.class,issue));
   assertEquals("UNPAID",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order));
   assertEquals(stockBefore,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku));
   assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?",Integer.class,order));
   jdbc.update("DELETE FROM payment_reconciliation_issue_audit WHERE issue_id=?",issue);
   jdbc.update("DELETE FROM payment_reconciliation_evidence WHERE issue_id=?",issue);
   jdbc.update("DELETE FROM payment_reconciliation_issues WHERE id=?",issue);
   issue=0;
   if(!type.equals("NO_ACTION")) {
    jdbc.update("INSERT INTO payment_reconciliation_issues(order_id,issue_type,status,summary,detected_at) VALUES (?,'AMOUNT_MISMATCH','OPEN','金额待核对',NOW())",order);
    issue=jdbc.queryForObject("SELECT id FROM payment_reconciliation_issues WHERE order_id=?",Long.class,order);
   }
  }
 }
 @Test void auditWriteFailureRollsBackEvidence() throws Exception {
  String path="/admin/payment-reconciliation/issues/"+issue;
  assertEquals(200,first.post(path+"/claim",Map.of()).statusCode());
  doAnswer(call -> { ReconciliationAudit row=call.getArgument(0); if("EVIDENCE".equals(row.getAction()))
    throw new DataAccessResourceFailureException("simulated"); return call.callRealMethod();
  }).when(auditMapper).insert(any(ReconciliationAudit.class));
  assertEquals(503,first.post(path+"/evidence",Map.of("billEvidence","账单未找到","localEvidence","本地已核实","channelQueryEvidence","渠道已核实")).statusCode());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_evidence WHERE issue_id=?",Integer.class,issue));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_issue_audit WHERE issue_id=?",Integer.class,issue));
  assertEquals("OPEN",jdbc.queryForObject("SELECT status FROM payment_reconciliation_issues WHERE id=?",String.class,issue));
 }
 @Test void closeHoldingRowLockPreventsLateEvidence() throws Exception {
  String path="/admin/payment-reconciliation/issues/"+issue;
  assertEquals(200,first.post(path+"/claim",Map.of()).statusCode());
  var proof=first.post(path+"/evidence",Map.of("billEvidence","账单已核实","localEvidence","本地已核实","channelQueryEvidence","渠道已核实"));
  long proofId=json.readTree(proof.body()).path("data").path("id").asLong();
  CountDownLatch locked=new CountDownLatch(1), release=new CountDownLatch(1), started=new CountDownLatch(1);
  AtomicBoolean once=new AtomicBoolean();
  doAnswer(call -> { jdbc.queryForObject("SELECT id FROM payment_reconciliation_issues WHERE id=? FOR UPDATE",Long.class,issue);
    Object row=issueMapper.selectById(issue); if(once.compareAndSet(false,true)) {
    locked.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS)); } return row;
  }).when(issueMapper).selectForUpdate(issue);
  try(var workers=Executors.newFixedThreadPool(2)) {
   var close=workers.submit(()->first.post(path+"/resolve",Map.of("evidenceId",proofId,"conclusionType","NO_ACTION","conclusion","核实完毕")).statusCode());
   try {
    assertTrue(locked.await(10,TimeUnit.SECONDS));
    var late=workers.submit(()->{ started.countDown(); return first.post(path+"/evidence",Map.of("billEvidence","后到","localEvidence","后到","channelQueryEvidence","后到")).statusCode(); });
    assertTrue(started.await(5,TimeUnit.SECONDS)); release.countDown();
    assertEquals(200,close.get(10,TimeUnit.SECONDS)); assertEquals(409,late.get(10,TimeUnit.SECONDS));
   } finally { release.countDown(); }
  }
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_evidence WHERE issue_id=?",Integer.class,issue));
  assertEquals("RESOLVED",jdbc.queryForObject("SELECT status FROM payment_reconciliation_issues WHERE id=?",String.class,issue));
 }
 @Test void twoAdministratorsCompeteForOneClaim() throws Exception {
  String path="/admin/payment-reconciliation/issues/"+issue+"/claim";
  CountDownLatch start=new CountDownLatch(1);
  try(var workers=Executors.newFixedThreadPool(2)) {
   var a=workers.submit(()->{start.await();return first.post(path,Map.of()).statusCode();});
   var b=workers.submit(()->{start.await();return second.post(path,Map.of()).statusCode();});
   start.countDown(); var statuses=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
   assertTrue(statuses.contains(200)); assertTrue(statuses.contains(409));
  }
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_issue_audit WHERE issue_id=? AND action='CLAIM'",Integer.class,issue));
 }
}
