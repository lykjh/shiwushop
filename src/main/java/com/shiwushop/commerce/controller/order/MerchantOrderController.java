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

    /**
     * 按游标查询某店铺的商家订单。拒绝旧的页码和总数参数，避免同一接口出现两套分页语义。
     * 店铺访问权限和游标查询由订单服务处理。
     *
     * @param id 店铺 ID
     * @param afterId 上一批最后一笔订单的 ID；首次查询可省略
     * @param size 本批最多返回的订单数，默认 20
     * @param request 当前请求，用于取得商家用户 ID
     * @return 订单列表及下一批游标
     */
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

    /**
     * 读取指定店铺的一笔订单详情。订单服务会结合当前用户、店铺和订单 ID 校验访问权限。
     *
     * @param shopId 店铺 ID
     * @param orderId 订单 ID
     * @param request 当前请求，用于取得商家用户 ID
     * @return 商家可查看的订单详情
     */
    @GetMapping("/{shopId}/orders/{orderId}")
    public Result<OrderDetailVO> orderDetail(@PathVariable Long shopId, @PathVariable Long orderId,
                                              HttpServletRequest request) {
        return Result.success(orderService.detailMerchant(SessionUser.currentId(request), shopId, orderId));
    }
}
