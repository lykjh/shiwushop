package com.shiwushop.aftersale.controller;

import jakarta.validation.Valid;
import com.shiwushop.aftersale.dto.AfterSaleCreateDTO;
import com.shiwushop.aftersale.dto.AfterSaleReviewDTO;
import com.shiwushop.aftersale.dto.ReturnReceiptDTO;
import com.shiwushop.aftersale.dto.ReturnShipmentDTO;
import com.shiwushop.aftersale.vo.AfterSaleVO;
import com.shiwushop.aftersale.vo.AfterSaleSliceVO;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.aftersale.service.AfterSaleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
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

    /**
     * 买家对本人订单发起售后申请。请求头的幂等键让重复提交复用同一申请结果。
     */
    @PostMapping("/orders/{orderId}/after-sales")
    public Result<AfterSaleVO> create(@PathVariable @Positive Long orderId,
                                      @RequestHeader("Idempotency-Key") @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64) String requestKey,
                                      @Valid @RequestBody AfterSaleCreateDTO body, HttpServletRequest request) {
        return Result.success(service.create(SessionUser.requireId(request), orderId, requestKey.strip(), body));
    }

    /** 买家按游标查看本人订单关联的售后申请。 */
    @GetMapping("/orders/{orderId}/after-sales")
    public Result<AfterSaleSliceVO> buyerList(@PathVariable @Positive Long orderId,
                                               @RequestParam(required = false) @Positive Long beforeId,
                                               @RequestParam(defaultValue = "20") int size,
                                               HttpServletRequest request) {
        return Result.success(service.buyerList(SessionUser.requireId(request), orderId, beforeId, size));
    }

    /** 买家查看本人售后申请详情和处理状态。 */
    @GetMapping("/after-sales/{caseId}")
    public Result<AfterSaleVO> buyerDetail(@PathVariable @Positive Long caseId, HttpServletRequest request) {
        return Result.success(service.buyerDetail(SessionUser.requireId(request), caseId));
    }

    /** 买家提交退货物流信息，服务层检查售后当前是否允许退货。 */
    @PostMapping("/after-sales/{caseId}/return-shipment")
    public Result<AfterSaleVO> sendReturn(@PathVariable @Positive Long caseId, @Valid @RequestBody ReturnShipmentDTO body,
                                          HttpServletRequest request) {
        return Result.success(service.sendReturn(SessionUser.requireId(request), caseId, body));
    }

    /** 商家查看所属店铺的一条售后申请详情。 */
    @GetMapping("/merchant/shops/{shopId}/after-sales/{caseId}")
    public Result<AfterSaleVO> merchantDetail(@PathVariable @Positive Long shopId, @PathVariable @Positive Long caseId,
                                               HttpServletRequest request) {
        return Result.success(service.merchantDetail(SessionUser.requireId(request), shopId, caseId));
    }

    /** 商家按状态和游标筛选店铺售后申请。 */
    @GetMapping("/merchant/shops/{shopId}/after-sales")
    public Result<AfterSaleSliceVO> merchantList(@PathVariable @Positive Long shopId,
                                                  @RequestParam(required = false) @Positive Long beforeId,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  HttpServletRequest request) {
        return Result.success(service.merchantList(SessionUser.requireId(request), shopId,
                beforeId, status, size));
    }

    /** 商家审核一条售后申请，服务层执行对应状态转换。 */
    @PostMapping("/merchant/shops/{shopId}/after-sales/{caseId}/review")
    public Result<AfterSaleVO> review(@PathVariable @Positive Long shopId, @PathVariable @Positive Long caseId,
                                       @Valid @RequestBody AfterSaleReviewDTO body, HttpServletRequest request) {
        return Result.success(service.review(SessionUser.requireId(request), shopId, caseId, body));
    }

    /** 商家登记收到买家退回的商品，并推动售后进入后续处理状态。 */
    @PostMapping("/merchant/shops/{shopId}/after-sales/{caseId}/receive")
    public Result<AfterSaleVO> receive(@PathVariable @Positive Long shopId, @PathVariable @Positive Long caseId,
                                        @Valid @RequestBody ReturnReceiptDTO body, HttpServletRequest request) {
        return Result.success(service.receiveReturn(SessionUser.requireId(request), shopId, caseId, body));
    }

    /** 商家为符合条件的售后申请发起退款。 */
    @PostMapping("/merchant/shops/{shopId}/after-sales/{caseId}/refund")
    public Result<AfterSaleVO> refund(@PathVariable @Positive Long shopId, @PathVariable @Positive Long caseId,
                                       HttpServletRequest request) {
        return Result.success(service.refund(SessionUser.requireId(request), shopId, caseId));
    }

    /** 商家主动向退款网关核对结果，并同步售后退款状态。 */
    @PostMapping("/merchant/shops/{shopId}/after-sales/{caseId}/refund/sync")
    public Result<AfterSaleVO> syncRefund(@PathVariable @Positive Long shopId, @PathVariable @Positive Long caseId,
                                           HttpServletRequest request) {
        return Result.success(service.syncRefund(SessionUser.requireId(request), shopId, caseId));
    }
}
