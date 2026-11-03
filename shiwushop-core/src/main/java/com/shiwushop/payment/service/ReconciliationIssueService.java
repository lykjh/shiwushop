package com.shiwushop.payment.service;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.payment.dto.*;
import com.shiwushop.payment.entity.*;
import com.shiwushop.payment.mapper.*;
import com.shiwushop.payment.vo.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service @RequiredArgsConstructor
public class ReconciliationIssueService {
 private final ReconciliationIssueMapper issues;
 private final ReconciliationEvidenceMapper evidence;
 private final ReconciliationAuditMapper audits;
 private final Clock businessClock;

 /** 以ID倒序读取差异，不改变处理状态。 */
 public ReconciliationIssuePage list(String status, Long afterId, int size) {
  List<ReconciliationIssue> rows=issues.selectSlice(status,afterId,size+1);
  boolean more=rows.size()>size;
  List<ReconciliationIssue> page=more ? rows.subList(0,size) : rows;
  return new ReconciliationIssuePage(page,more,more ? page.getLast().getId() : null);
 }
 /** 查看本地上下文、人工证据和不可变审计。 */
 public ReconciliationIssueDetail detail(Long id) {
  ReconciliationIssue issue=issues.selectById(id);
  if (issue==null) throw new BusinessException(404,"对账差异不存在");
  return new ReconciliationIssueDetail(issue,evidence.selectForIssue(id),audits.selectForIssue(id));
 }
 /** 行锁串行领取，只允许一名管理员处理。 */
 @Transactional public ReconciliationIssue claim(Long id, Long actor) {
  ReconciliationIssue issue=locked(id);
  if (!"OPEN".equals(issue.getStatus())) throw new BusinessException(409,"差异已关闭");
  if (issue.getClaimedBy()!=null) {
   if (actor.equals(issue.getClaimedBy())) return issue;
   throw new BusinessException(409,"差异已被其他管理员领取");
  }
  if (issues.claim(id,actor)!=1) throw new BusinessException(409,"差异领取冲突");
  issue.setClaimedBy(actor);
  audit(id,actor,"CLAIM",null,null);
  BusinessEventLog.committed("payment","reconciliation_claimed","reconciliation_issue",id);
  return issues.selectById(id);
 }
 /** 领取人对未关闭差异追加人工核实记录，不覆盖已有证据。 */
 @Transactional public ReconciliationEvidence addEvidence(Long id, Long actor, ReconciliationEvidenceDTO dto) {
  ReconciliationIssue issue=locked(id);
  requireOwnerOpen(issue,actor);
  ReconciliationEvidence row=new ReconciliationEvidence();
  row.setIssueId(id); row.setActorId(actor); row.setBillEvidence(dto.billEvidence());
  row.setLocalEvidence(dto.localEvidence()); row.setChannelQueryEvidence(dto.channelQueryEvidence());
  row.setRequestId(MDC.get("requestId")); row.setCreatedAt(LocalDateTime.now(businessClock));
  evidence.insert(row); audit(id,actor,"EVIDENCE",row.getId(),null);
  BusinessEventLog.committed("payment","reconciliation_evidence_added","reconciliation_issue",id);
  return row;
 }
 /** 仅领取人以指定证据关闭差异；完全相同的重试返回既有结果。 */
 @Transactional public ReconciliationIssue resolve(Long id, Long actor, ReconciliationResolveDTO dto) {
  ReconciliationIssue issue=locked(id);
  if ("RESOLVED".equals(issue.getStatus())) {
   if (actor.equals(issue.getClaimedBy()) && Objects.equals(dto.evidenceId(),issue.getResolvedEvidenceId())
     && Objects.equals(dto.conclusionType(),issue.getConclusionType()) && Objects.equals(dto.conclusion(),issue.getConclusion())
     && Objects.equals(dto.manualHandlingReference(),issue.getManualHandlingReference())) return issue;
   throw new BusinessException(409,"差异已关闭且结论不一致");
  }
  requireOwnerOpen(issue,actor);
  ReconciliationEvidence selected=evidence.selectById(dto.evidenceId());
  if (selected==null || !id.equals(selected.getIssueId())) throw new BusinessException(409,"证据不属于该差异");
  LocalDateTime now=LocalDateTime.now(businessClock);
  if (issues.resolve(id,actor,now,dto.evidenceId(),dto.conclusionType(),dto.conclusion(),dto.manualHandlingReference())!=1)
   throw new BusinessException(409,"差异关闭冲突");
  issue.setStatus("RESOLVED"); issue.setResolvedAt(now); issue.setResolvedEvidenceId(dto.evidenceId());
  issue.setConclusionType(dto.conclusionType()); issue.setConclusion(dto.conclusion()); issue.setManualHandlingReference(dto.manualHandlingReference());
  audit(id,actor,"RESOLVE",dto.evidenceId(),dto);
  BusinessEventLog.committed("payment","reconciliation_resolved","reconciliation_issue",id);
  return issues.selectById(id);
 }
 private ReconciliationIssue locked(Long id) {
  ReconciliationIssue issue=issues.selectForUpdate(id);
  if (issue==null) throw new BusinessException(404,"对账差异不存在");
  return issue;
 }
 private void requireOwnerOpen(ReconciliationIssue issue, Long actor) {
  if (!"OPEN".equals(issue.getStatus()) || !actor.equals(issue.getClaimedBy()))
   throw new BusinessException(409,"差异未由当前管理员领取或已关闭");
 }
 private void audit(Long id, Long actor, String action, Long evidenceId, ReconciliationResolveDTO dto) {
  ReconciliationAudit row=new ReconciliationAudit(); row.setIssueId(id); row.setActorId(actor); row.setAction(action);
  row.setEvidenceId(evidenceId); row.setRequestId(MDC.get("requestId")); row.setOccurredAt(LocalDateTime.now(businessClock));
  if (dto!=null) { row.setConclusionType(dto.conclusionType()); row.setConclusion(dto.conclusion()); row.setManualHandlingReference(dto.manualHandlingReference()); }
  audits.insert(row);
 }
}
