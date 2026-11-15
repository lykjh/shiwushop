package com.shiwushop.aftersale.controller;

import com.shiwushop.aftersale.dto.AdminRefundReconcileDTO;
import com.shiwushop.aftersale.service.AfterSaleService;
import com.shiwushop.aftersale.vo.AfterSaleVO;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AdminAfterSaleController {
    private final AfterSaleService service;

    /** 管理员查看售后详情与历史退货目的地。 */
    @GetMapping("/admin/after-sales/{caseId}")
    public Result<AfterSaleVO> detail(@PathVariable @Positive Long caseId, HttpServletRequest request) {
        return Result.success(service.adminDetail(SessionUser.requireId(request), caseId));
    }

    @GetMapping("/admin/after-sales/failed-refunds")
    public Result<AfterSaleService.FailedRefundPage> failedRefunds(
            @RequestParam(required = false) @Positive Long beforeCaseId,
            @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        return Result.success(service.failedRefunds(SessionUser.requireId(request), beforeCaseId, size));
    }

    @PostMapping("/admin/after-sales/{caseId}/refund/reconcile")
    public Result<AfterSaleVO> reconcileRefund(@PathVariable @Positive Long caseId,
                                               @Valid @RequestBody AdminRefundReconcileDTO body,
                                               HttpServletRequest request) {
        return Result.success(service.reconcileRefund(SessionUser.requireId(request), caseId, body));
    }
}
