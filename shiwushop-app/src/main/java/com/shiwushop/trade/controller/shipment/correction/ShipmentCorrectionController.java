package com.shiwushop.trade.controller.shipment.correction;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.auth.AdminAuth;
import com.shiwushop.trade.dto.shipment.correction.ShipmentCorrectionRequestDTO;
import com.shiwushop.trade.dto.shipment.correction.ShipmentCorrectionDecisionDTO;
import com.shiwushop.trade.dto.shipment.correction.ShipmentCorrectionStatus;
import com.shiwushop.trade.vo.shipment.correction.ShipmentCorrectionHistoryPageVO;
import com.shiwushop.trade.vo.shipment.correction.ShipmentCorrectionAdminPageVO;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import com.shiwushop.trade.service.ShipmentCorrectionService;
import com.shiwushop.trade.vo.shipment.correction.ShipmentCorrectionRequestVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ShipmentCorrectionController {
    private final ShipmentCorrectionService corrections;
    private final AdminAuth adminAuth;

    /** 店主为已发货订单提交运单更正。 */
    @PostMapping("/merchant/shops/{shopId}/orders/{orderId}/shipment/corrections")
    public Result<ShipmentCorrectionRequestVO> request(@PathVariable @Positive Long shopId,
            @PathVariable @Positive Long orderId, @Valid @RequestBody ShipmentCorrectionRequestDTO body,
            HttpServletRequest request) {
        return Result.success(corrections.request(SessionUser.requireId(request), shopId, orderId, body));
    }

    /** 管理员批准或驳回申请。 */
    @PostMapping("/admin/shipment-corrections/{id}/decision")
    public Result<ShipmentCorrectionRequestVO> decide(@PathVariable @Positive Long id,
            @Valid @RequestBody ShipmentCorrectionDecisionDTO body, HttpServletRequest request) {
        return Result.success(corrections.decide(adminAuth.requireAdmin(request), id, body));
    }

    /** 买家读取订单中已生效的更正历史。 */
    @GetMapping("/orders/{orderId}/shipment/corrections")
    public Result<ShipmentCorrectionHistoryPageVO> buyerHistory(@PathVariable @Positive Long orderId,
            HttpServletRequest request) {
        return Result.success(corrections.buyerHistory(SessionUser.requireId(request), orderId));
    }

    /** 当前店主读取订单中已生效的更正历史。 */
    @GetMapping("/merchant/shops/{shopId}/orders/{orderId}/shipment/corrections")
    public Result<ShipmentCorrectionHistoryPageVO> merchantHistory(@PathVariable @Positive Long shopId,
            @PathVariable @Positive Long orderId, HttpServletRequest request) {
        return Result.success(corrections.merchantHistory(SessionUser.requireId(request), shopId, orderId));
    }

    /** 管理员按状态和游标读取申请。 */
    @GetMapping("/admin/shipment-corrections")
    public Result<ShipmentCorrectionAdminPageVO> list(
            @RequestParam(defaultValue = "PENDING") ShipmentCorrectionStatus status,
            @RequestParam(required = false) @Positive Long afterId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, HttpServletRequest request) {
        adminAuth.requireAdmin(request);
        return Result.success(corrections.adminList(status, afterId, size));
    }
}
