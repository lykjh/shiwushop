package com.shiwushop.payment.controller;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.AdminAuth;
import com.shiwushop.payment.dto.PaymentBillDownloadDTO;
import com.shiwushop.payment.service.impl.PaymentBillService;
import com.shiwushop.payment.vo.PaymentBillVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/admin/payment-bills") @RequiredArgsConstructor
public class AdminPaymentBillController {
 private final AdminAuth auth;
 private final PaymentBillService bills;
 /** 请求并私密留存某日原始交易账单，结果明确未解析。 */
 @PostMapping public Result<PaymentBillVO> download(@Valid @RequestBody PaymentBillDownloadDTO dto,HttpServletRequest request) {
  return Result.success(bills.download(dto.billDate(),auth.requireAdmin(request)));
 }
 /** 查询已有批次及未解析状态。 */
 @GetMapping("/{id}") public Result<PaymentBillVO> detail(@PathVariable @Positive Long id,HttpServletRequest request) {
  auth.requireAdmin(request); return Result.success(bills.detail(id));
 }
 /** 管理员取回原始账单，不经公开静态资源路径。 */
 @GetMapping("/{id}/file") public ResponseEntity<FileSystemResource> file(@PathVariable @Positive Long id,HttpServletRequest request) {
  auth.requireAdmin(request);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
   .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=payment-bill-"+id+".bin").body(bills.file(id));
 }
}
