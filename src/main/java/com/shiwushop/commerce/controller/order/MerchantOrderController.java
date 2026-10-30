package com.shiwushop.commerce.controller.order;

import com.shiwushop.commerce.service.OrderService;
import com.shiwushop.commerce.vo.order.OrderDetailVO;
import com.shiwushop.commerce.vo.order.OrderSliceVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 商家订单入口留在原有 URL，按订单职责与店铺资料接口分开。 */
@RestController
@RequestMapping("/merchant/shops")
@RequiredArgsConstructor
public class MerchantOrderController {
    private final OrderService orderService;

    @GetMapping("/{id}/orders")
    public Result<OrderSliceVO> orders(@PathVariable Long id,
                                      @RequestParam(required = false) Long afterId,
                                      @RequestParam(defaultValue = "20") int size,
                                      HttpServletRequest request) {
        Long userId = SessionUser.currentId(request);
        if (request.getParameterMap().containsKey("page") || request.getParameterMap().containsKey("includeTotal"))
            throw new BusinessException(400, "订单列表使用游标翻页");
        return Result.success(orderService.listMerchant(userId, id, afterId, size));
    }

    @GetMapping("/{shopId}/orders/{orderId}")
    public Result<OrderDetailVO> orderDetail(@PathVariable Long shopId, @PathVariable Long orderId,
                                              HttpServletRequest request) {
        return Result.success(orderService.detailMerchant(SessionUser.currentId(request), shopId, orderId));
    }
}
