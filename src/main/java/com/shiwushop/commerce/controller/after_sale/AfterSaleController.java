package com.shiwushop.commerce.controller.after_sale;

import com.shiwushop.commerce.dto.after_sale.AfterSaleCreateDTO;
import com.shiwushop.commerce.dto.after_sale.AfterSaleReviewDTO;
import com.shiwushop.commerce.dto.after_sale.ReturnReceiptDTO;
import com.shiwushop.commerce.dto.after_sale.ReturnShipmentDTO;
import com.shiwushop.commerce.vo.after_sale.AfterSaleVO;
import com.shiwushop.commerce.vo.after_sale.AfterSaleSliceVO;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.commerce.service.AfterSaleService;
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

    /**
     * 买家对本人订单发起售后申请。请求头的幂等键让重复提交复用同一申请结果。
     */
    @PostMapping("/orders/{orderId}/after-sales")
    public Result<AfterSaleVO> create(@PathVariable Long orderId,
                                      @RequestHeader("Idempotency-Key") String requestKey,
                                      @RequestBody AfterSaleCreateDTO body, HttpServletRequest request) {
        return Result.success(service.create(SessionUser.requireId(request), orderId, requestKey, body));
    }

    /** 买家按游标查看本人订单关联的售后申请。 */
    @GetMapping("/orders/{orderId}/after-sales")
    public Result<AfterSaleSliceVO> buyerList(@PathVariable Long orderId,
                                               @RequestParam(required = false) Long beforeId,
                                               @RequestParam(defaultValue = "20") int size,
                                               HttpServletRequest request) {
        return Result.success(service.buyerList(SessionUser.requireId(request), orderId, beforeId, size));
    }

    /** 买家查看本人售后申请详情和处理状态。 */
    @GetMapping("/after-sales/{caseId}")
    public Result<AfterSaleVO> buyerDetail(@PathVariable Long caseId, HttpServletRequest request) {
        return Result.success(service.buyerDetail(SessionUser.requireId(request), caseId));
    }

    /** 买家提交退货物流信息，服务层检查售后当前是否允许退货。 */
    @PostMapping("/after-sales/{caseId}/return-shipment")
    public Result<AfterSaleVO> sendReturn(@PathVariable Long caseId, @RequestBody ReturnShipmentDTO body,
                                          HttpServletRequest request) {
        return Result.success(service.sendReturn(SessionUser.requireId(request), caseId, body));
    }

    /** 商家查看所属店铺的一条售后申请详情。 */
    @GetMapping("/merchant/shops/{shopId}/after-sales/{caseId}")
    public Result<AfterSaleVO> merchantDetail(@PathVariable Long shopId, @PathVariable Long caseId,
                                               HttpServletRequest request) {
        return Result.success(service.merchantDetail(SessionUser.requireId(request), shopId, caseId));
    }

    /** 商家按状态和游标筛选店铺售后申请。 */
    @GetMapping("/merchant/shops/{shopId}/after-sales")
    public Result<AfterSaleSliceVO> merchantList(@PathVariable Long shopId,
                                                  @RequestParam(required = false) Long beforeId,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  HttpServletRequest request) {
        return Result.success(service.merchantList(SessionUser.requireId(request), shopId,
                beforeId, status, size));
    }

    /** 商家审核一条售后申请，服务层执行对应状态转换。 */
    @PostMapping("/merchant/shops/{shopId}/after-sales/{caseId}/review")
    public Result<AfterSaleVO> review(@PathVariable Long shopId, @PathVariable Long caseId,
                                       @RequestBody AfterSaleReviewDTO body, HttpServletRequest request) {
        return Result.success(service.review(SessionUser.requireId(request), shopId, caseId, body));
    }

    /** 商家登记收到买家退回的商品，并推动售后进入后续处理状态。 */
    @PostMapping("/merchant/shops/{shopId}/after-sales/{caseId}/receive")
    public Result<AfterSaleVO> receive(@PathVariable Long shopId, @PathVariable Long caseId,
                                        @RequestBody ReturnReceiptDTO body, HttpServletRequest request) {
        return Result.success(service.receiveReturn(SessionUser.requireId(request), shopId, caseId, body));
    }

    /** 商家为符合条件的售后申请发起退款。 */
    @PostMapping("/merchant/shops/{shopId}/after-sales/{caseId}/refund")
    public Result<AfterSaleVO> refund(@PathVariable Long shopId, @PathVariable Long caseId,
                                       HttpServletRequest request) {
        return Result.success(service.refund(SessionUser.requireId(request), shopId, caseId));
    }

    /** 商家主动向退款网关核对结果，并同步售后退款状态。 */
    @PostMapping("/merchant/shops/{shopId}/after-sales/{caseId}/refund/sync")
    public Result<AfterSaleVO> syncRefund(@PathVariable Long shopId, @PathVariable Long caseId,
                                           HttpServletRequest request) {
        return Result.success(service.syncRefund(SessionUser.requireId(request), shopId, caseId));
    }
}
