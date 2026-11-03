package com.shiwushop.unit.payment;
import com.shiwushop.common.BusinessException;
import com.shiwushop.payment.dto.*;
import com.shiwushop.payment.entity.*;
import com.shiwushop.payment.mapper.*;
import com.shiwushop.payment.service.ReconciliationIssueService;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconciliationIssueServiceTests {
 final ReconciliationIssueMapper issues=mock(ReconciliationIssueMapper.class);
 final ReconciliationEvidenceMapper evidence=mock(ReconciliationEvidenceMapper.class);
 final ReconciliationAuditMapper audits=mock(ReconciliationAuditMapper.class);
 final ReconciliationIssueService service=new ReconciliationIssueService(issues,evidence,audits,Clock.systemUTC());
 @Test void claimIsIdempotentAndOtherAdminConflicts() {
  var row=new ReconciliationIssue(); row.setId(10L); row.setStatus("OPEN");
  when(issues.selectForUpdate(10L)).thenReturn(row); when(issues.selectById(10L)).thenReturn(row); when(issues.claim(10L,7L)).thenReturn(1);
  assertEquals(7L,service.claim(10L,7L).getClaimedBy());
  service.claim(10L,7L);
  assertEquals(409,assertThrows(BusinessException.class,()->service.claim(10L,8L)).getCode());
  verify(audits,times(1)).insert(any(ReconciliationAudit.class));
 }
 @Test void resolveRejectsForeignEvidenceAndConflictingRetry() {
  var row=new ReconciliationIssue(); row.setId(10L); row.setStatus("OPEN"); row.setClaimedBy(7L);
  var proof=new ReconciliationEvidence(); proof.setId(99L); proof.setIssueId(11L);
  when(issues.selectForUpdate(10L)).thenReturn(row); when(evidence.selectById(99L)).thenReturn(proof);
  var dto=new ReconciliationResolveDTO(99L,"NO_ACTION","无须处理",null);
  assertEquals(409,assertThrows(BusinessException.class,()->service.resolve(10L,7L,dto)).getCode());
  row.setStatus("RESOLVED"); row.setResolvedEvidenceId(99L); row.setConclusionType("NO_ACTION"); row.setConclusion("无须处理");
  assertSame(row,service.resolve(10L,7L,dto));
  var changed=new ReconciliationResolveDTO(99L,"NO_ACTION","改结论",null);
  assertEquals(409,assertThrows(BusinessException.class,()->service.resolve(10L,7L,changed)).getCode());
  verifyNoInteractions(audits);
 }
 @Test void allFiveConclusionTypesCloseWithEvidence() {
  for(String type : new String[]{"CHANNEL_ONLY","LOCAL_ONLY","AMOUNT_MISMATCH","REFUND_STATUS_MISMATCH","NO_ACTION"}) {
   var row=new ReconciliationIssue(); row.setId(10L); row.setStatus("OPEN"); row.setClaimedBy(7L);
   var proof=new ReconciliationEvidence(); proof.setId(99L); proof.setIssueId(10L);
   when(issues.selectForUpdate(10L)).thenReturn(row); when(issues.selectById(10L)).thenReturn(row); when(evidence.selectById(99L)).thenReturn(proof);
   when(issues.resolve(eq(10L),eq(7L),any(),eq(99L),eq(type),eq("说明"),isNull())).thenReturn(1);
   assertEquals(type,service.resolve(10L,7L,new ReconciliationResolveDTO(99L,type,"说明",null)).getConclusionType());
   assertEquals("RESOLVED",row.getStatus());
  }
 }
}
