package com.shiwushop.controller.after_sale;

import com.shiwushop.dto.after_sale.AfterSaleCreateDTO;
import com.shiwushop.dto.after_sale.AfterSaleReviewDTO;
import com.shiwushop.dto.after_sale.ReturnReceiptDTO;
import com.shiwushop.dto.after_sale.ReturnShipmentDTO;
import com.shiwushop.vo.after_sale.AfterSaleVO;
import com.shiwushop.vo.after_sale.AfterSaleSliceVO;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.service.AfterSaleService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AfterSaleController {
    private final AfterSaleService service;

    @PostMapping("/orders/{orderId}/after-sales")
    public Result<AfterSaleVO> create(@PathVariable Long orderId,
                                      @RequestHeader("Idempotency-Key") String requestKey,
                                      @RequestBody AfterSaleCreateDTO body, HttpServletRequest request) {
        return Result.success(service.create(SessionUser.requireId(request), orderId, requestKey, body));
    }

    @GetMapping("/orders/{orderId}/after-sales")
    public Result<AfterSaleSliceVO> buyerList(@PathVariable Long orderId,
                                               @RequestParam(required = false) Long beforeId,
                                               @RequestParam(defaultValue = "20") int size,
                                               HttpServletRequest request) {
        return Result.success(service.buyerList(SessionUser.requireId(request), orderId, beforeId, size));
    }

    @GetMapping("/after-sales/{caseId}")
    public Result<AfterSaleVO> buyerDetail(@PathVariable Long caseId, HttpServletRequest request) {
        return Result.success(service.buyerDetail(SessionUser.requireId(request), caseId));
    }

    @PostMapping("/after-sales/{caseId}/return-shipment")
    public Result<AfterSaleVO> sendReturn(@PathVariable Long caseId, @RequestBody ReturnShipmentDTO body,
                                          HttpServletRequest request) {
        return Result.success(service.sendReturn(SessionUser.requireId(request), caseId, body));
    }

    @GetMapping("/merchant/shops/{shopId}/after-sales/{caseId}")
    public Result<AfterSaleVO> merchantDetail(@PathVariable Long shopId, @PathVariable Long caseId,
                                               HttpServletRequest request) {
        return Result.success(service.merchantDetail(SessionUser.requireId(request), shopId, caseId));
    }

    @GetMapping("/merchant/shops/{shopId}/after-sales")
    public Result<AfterSaleSliceVO> merchantList(@PathVariable Long shopId,
                                                  @RequestParam(required = false) Long beforeId,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  HttpServletRequest request) {
        return Result.success(service.merchantList(SessionUser.requireId(request), shopId,
                beforeId, status, size));
    }

    @PostMapping("/merchant/shops/{shopId}/after-sales/{caseId}/review")
    public Result<AfterSaleVO> review(@PathVariable Long shopId, @PathVariable Long caseId,
                                       @RequestBody AfterSaleReviewDTO body, HttpServletRequest request) {
        return Result.success(service.review(SessionUser.requireId(request), shopId, caseId, body));
    }

    @PostMapping("/merchant/shops/{shopId}/after-sales/{caseId}/receive")
    public Result<AfterSaleVO> receive(@PathVariable Long shopId, @PathVariable Long caseId,
                                        @RequestBody ReturnReceiptDTO body, HttpServletRequest request) {
        return Result.success(service.receiveReturn(SessionUser.requireId(request), shopId, caseId, body));
    }

    @PostMapping("/merchant/shops/{shopId}/after-sales/{caseId}/refund")
    public Result<AfterSaleVO> refund(@PathVariable Long shopId, @PathVariable Long caseId,
                                       HttpServletRequest request) {
        return Result.success(service.refund(SessionUser.requireId(request), shopId, caseId));
    }

    @PostMapping("/merchant/shops/{shopId}/after-sales/{caseId}/refund/sync")
    public Result<AfterSaleVO> syncRefund(@PathVariable Long shopId, @PathVariable Long caseId,
                                           HttpServletRequest request) {
        return Result.success(service.syncRefund(SessionUser.requireId(request), shopId, caseId));
    }
}
