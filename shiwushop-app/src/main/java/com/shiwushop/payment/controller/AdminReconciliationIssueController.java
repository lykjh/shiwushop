package com.shiwushop.payment.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.AdminAuth;
import com.shiwushop.payment.dto.*;
import com.shiwushop.payment.entity.*;
import com.shiwushop.payment.service.ReconciliationIssueService;
import com.shiwushop.payment.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/admin/payment-reconciliation/issues") @RequiredArgsConstructor
public class AdminReconciliationIssueController {
 private final AdminAuth auth;
 private final ReconciliationIssueService service;
 /** 按 ID 游标读取差异。 */
 @GetMapping public Result<ReconciliationIssuePage> list(@RequestParam(required=false) @Pattern(regexp="OPEN|RESOLVED") String status,
  @RequestParam(required=false) @Positive Long afterId, @RequestParam(defaultValue="20") @Min(1) @Max(100) int size, HttpServletRequest request) {
  auth.requireAdmin(request); return Result.success(service.list(status,afterId,size));
 }
 /** 读取差异及管理员核实记录。 */
 @GetMapping("/{id}") public Result<ReconciliationIssueDetail> detail(@PathVariable @Positive Long id, HttpServletRequest request) {
  auth.requireAdmin(request); return Result.success(service.detail(id));
 }
 /** 当前管理员领取差异。 */
 @PostMapping("/{id}/claim") public Result<ReconciliationIssue> claim(@PathVariable @Positive Long id, HttpServletRequest request) {
  return Result.success(service.claim(id,auth.requireAdmin(request)));
 }
 /** 追加三类人工证据摘要。 */
 @PostMapping("/{id}/evidence") public Result<ReconciliationEvidence> evidence(@PathVariable @Positive Long id,
  @Valid @RequestBody ReconciliationEvidenceDTO dto, HttpServletRequest request) {
  return Result.success(service.addEvidence(id,auth.requireAdmin(request),dto));
 }
 /** 按选定证据保存结论并关闭。 */
 @PostMapping("/{id}/resolve") public Result<ReconciliationIssue> resolve(@PathVariable @Positive Long id,
  @Valid @RequestBody ReconciliationResolveDTO dto, HttpServletRequest request) {
  return Result.success(service.resolve(id,auth.requireAdmin(request),dto));
 }
}
