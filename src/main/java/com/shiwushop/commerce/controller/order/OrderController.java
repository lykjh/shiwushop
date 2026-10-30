package com.shiwushop.commerce.controller.order;

import com.shiwushop.commerce.vo.order.OrderSliceVO;
import com.shiwushop.commerce.vo.order.OrderDetailVO;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.Result;
import com.shiwushop.commerce.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    /** 读取当前买家的指定订单详情；订单归属由服务层确认。 */
    @GetMapping("/{id}")
    public Result<OrderDetailVO> detail(@PathVariable Long id, HttpServletRequest request) {
        return Result.success(orderService.detail(SessionUser.requireId(request), id));
    }

    /** 使用订单 ID 游标列出当前买家的订单；拒绝页码和总数参数。 */
    @GetMapping
    public Result<OrderSliceVO> list(@RequestParam(required = false) Long afterId,
                                    @RequestParam(defaultValue = "20") int size,
                                    HttpServletRequest request) {
        Long userId = SessionUser.requireId(request);
        if (request.getParameterMap().containsKey("page") || request.getParameterMap().containsKey("includeTotal"))
            throw new BusinessException(400, "订单列表使用游标翻页");
        return Result.success(orderService.list(userId, afterId, size));
    }

}
