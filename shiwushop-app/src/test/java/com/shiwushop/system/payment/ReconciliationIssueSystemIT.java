package com.shiwushop.system.payment;
import com.shiwushop.support.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
/** 使用已有隔离差异记录走管理员证据、领取和关闭的真实 HTTP 路径。 */
class ReconciliationIssueSystemIT {
 @Test void administratorResolvesIssueWithoutChangingBusinessState() throws Exception {
  String base=SystemEnvironment.base(); var jdbc=SystemEnvironment.database(); var json=new ObjectMapper();
  try(var fixture=new CommerceFixture(jdbc,base); var accounts=new TestAccounts(jdbc);
      var admin=CommerceFixture.login(base,accounts.create("ADMIN","Shop123!"))) {
   long order=fixture.order("system-reconciliation");
   jdbc.update("INSERT INTO payment_reconciliation_issues(order_id,issue_type,status,summary,detected_at) VALUES (?,'AMOUNT_MISMATCH','OPEN','金额待核对',NOW())",order);
   long issue=jdbc.queryForObject("SELECT id FROM payment_reconciliation_issues WHERE order_id=?",Long.class,order);
   try {
    String path="/admin/payment-reconciliation/issues/"+issue;
    assertEquals(403,fixture.client.get(path).statusCode());
    assertEquals(200,admin.post(path+"/claim",Map.of()).statusCode());
    var evidence=admin.post(path+"/evidence",Map.of("billEvidence","账单未找到并已核实","localEvidence","本地流水未找到并已核实","channelQueryEvidence","渠道查询未找到并已核实"));
    assertEquals(200,evidence.statusCode(),evidence.body()); long proof=json.readTree(evidence.body()).path("data").path("id").asLong();
    var resolved=admin.post(path+"/resolve",Map.of("evidenceId",proof,"conclusionType","NO_ACTION","conclusion","核实后无需处理"));
    assertEquals(200,resolved.statusCode(),resolved.body());
    assertEquals("RESOLVED",jdbc.queryForObject("SELECT status FROM payment_reconciliation_issues WHERE id=?",String.class,issue));
    assertEquals("UNPAID",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order));
    assertEquals(19,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?",Integer.class,order));
   } finally {
    jdbc.update("DELETE FROM payment_reconciliation_issue_audit WHERE issue_id=?",issue);
    jdbc.update("DELETE FROM payment_reconciliation_evidence WHERE issue_id=?",issue);
    jdbc.update("DELETE FROM payment_reconciliation_issues WHERE id=?",issue);
   }
  }
 }
}
