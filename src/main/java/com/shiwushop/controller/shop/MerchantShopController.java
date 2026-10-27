package com.shiwushop.controller.shop;

import com.shiwushop.dto.shop.ShopProfileUpdateDTO;
import com.shiwushop.vo.order.OrderDetailVO;
import com.shiwushop.vo.shop.ShopVO;
import com.shiwushop.vo.order.OrderSliceVO;

import com.shiwushop.common.Result;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.service.ShopService;
import com.shiwushop.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/merchant/shops")
@RequiredArgsConstructor
public class MerchantShopController {

    private final ShopService shopService;
    private final OrderService orderService;
    @PutMapping("/{id}")
    public Result<ShopVO> update(
            @PathVariable Long id,
            @Valid @RequestBody ShopProfileUpdateDTO dto,
            HttpServletRequest request
            ){
        return Result.success(shopService.updateProfile(id,SessionUser.currentId(request),dto));
    }

    @GetMapping
    public Result<List<ShopVO>> list(HttpServletRequest request) {
        return Result.success(shopService.listMine(SessionUser.currentId(request)));
    }

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
    public Result<OrderDetailVO> orderDetail(@PathVariable Long shopId, @PathVariable Long orderId, HttpServletRequest request) {
        Long userId = SessionUser.currentId(request);
        return Result.success(orderService.detailMerchant(userId, shopId, orderId));
    }
}
