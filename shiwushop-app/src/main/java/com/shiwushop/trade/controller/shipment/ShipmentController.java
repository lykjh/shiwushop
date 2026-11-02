package com.shiwushop.trade.controller.shipment;

import jakarta.validation.Valid;
import com.shiwushop.trade.dto.shipment.ShipmentCreateDTO;
import com.shiwushop.trade.vo.shipment.ShipmentVO;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.trade.service.ShipmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ShipmentController {
    private final ShipmentService shipmentService;

    /** 商家为指定店铺订单提交发货信息；服务层检查商家权限和订单状态。 */
    @PostMapping("/merchant/shops/{shopId}/orders/{orderId}/shipment")
    public Result<ShipmentVO> ship(@PathVariable @Positive Long shopId, @PathVariable @Positive Long orderId,
                                   @Valid @RequestBody ShipmentCreateDTO body, HttpServletRequest request) {
        return Result.success(shipmentService.ship(SessionUser.requireId(request), shopId, orderId, body));
    }

    /** 商家查看指定店铺订单的物流信息。 */
    @GetMapping("/merchant/shops/{shopId}/orders/{orderId}/shipment")
    public Result<ShipmentVO> merchantDetail(@PathVariable @Positive Long shopId, @PathVariable @Positive Long orderId,
                                             HttpServletRequest request) {
        return Result.success(shipmentService.merchantDetail(SessionUser.requireId(request), shopId, orderId));
    }

    /** 买家查看本人订单的物流信息。 */
    @GetMapping("/orders/{orderId}/shipment")
    public Result<ShipmentVO> buyerDetail(@PathVariable @Positive Long orderId, HttpServletRequest request) {
        return Result.success(shipmentService.buyerDetail(SessionUser.requireId(request), orderId));
    }

    /** 买家确认本人订单收货，服务层处理物流状态和重复确认。 */
    @PostMapping("/orders/{orderId}/shipment/confirm")
    public Result<ShipmentVO> confirm(@PathVariable @Positive Long orderId, HttpServletRequest request) {
        return Result.success(shipmentService.confirmByBuyer(SessionUser.requireId(request), orderId));
    }
}
