package com.shiwushop.controller.shipment;

import com.shiwushop.dto.shipment.ShipmentCreateDTO;
import com.shiwushop.vo.shipment.ShipmentVO;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.service.ShipmentService;
import jakarta.servlet.http.HttpServletRequest;
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

    @PostMapping("/merchant/shops/{shopId}/orders/{orderId}/shipment")
    public Result<ShipmentVO> ship(@PathVariable Long shopId, @PathVariable Long orderId,
                                   @RequestBody ShipmentCreateDTO body, HttpServletRequest request) {
        return Result.success(shipmentService.ship(SessionUser.requireId(request), shopId, orderId, body));
    }

    @GetMapping("/merchant/shops/{shopId}/orders/{orderId}/shipment")
    public Result<ShipmentVO> merchantDetail(@PathVariable Long shopId, @PathVariable Long orderId,
                                             HttpServletRequest request) {
        return Result.success(shipmentService.merchantDetail(SessionUser.requireId(request), shopId, orderId));
    }

    @GetMapping("/orders/{orderId}/shipment")
    public Result<ShipmentVO> buyerDetail(@PathVariable Long orderId, HttpServletRequest request) {
        return Result.success(shipmentService.buyerDetail(SessionUser.requireId(request), orderId));
    }

    @PostMapping("/orders/{orderId}/shipment/confirm")
    public Result<ShipmentVO> confirm(@PathVariable Long orderId, HttpServletRequest request) {
        return Result.success(shipmentService.confirmByBuyer(SessionUser.requireId(request), orderId));
    }
}
