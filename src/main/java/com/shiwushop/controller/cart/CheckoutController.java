package com.shiwushop.controller.cart;

import com.shiwushop.dto.cart.CheckoutDTO;
import com.shiwushop.dto.cart.DirectCheckoutDTO;
import com.shiwushop.vo.order.CheckoutVO;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.Result;
import com.shiwushop.service.CartService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/checkouts")
@RequiredArgsConstructor
public class CheckoutController {
    private final CartService cartService;

    @PostMapping
    public Result<CheckoutVO> checkout(@Valid @RequestBody CheckoutDTO dto, HttpServletRequest request) {
        return Result.success(cartService.checkoutSelected(SessionUser.requireId(request), dto));
    }

    @PostMapping("/direct")
    public Result<CheckoutVO> direct(@Valid @RequestBody DirectCheckoutDTO dto, HttpServletRequest request) {
        return Result.success(cartService.checkoutDirect(SessionUser.requireId(request), dto));
    }

}
