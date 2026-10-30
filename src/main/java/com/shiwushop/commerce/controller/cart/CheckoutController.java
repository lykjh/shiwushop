package com.shiwushop.commerce.controller.cart;

import com.shiwushop.commerce.dto.cart.CheckoutDTO;
import com.shiwushop.commerce.dto.cart.DirectCheckoutDTO;
import com.shiwushop.commerce.vo.order.CheckoutVO;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.Result;
import com.shiwushop.commerce.service.CheckoutService;
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
    private final CheckoutService checkoutService;

    @PostMapping
    public Result<CheckoutVO> checkout(@Valid @RequestBody CheckoutDTO dto, HttpServletRequest request) {
        return Result.success(checkoutService.checkoutSelected(SessionUser.requireId(request), dto));
    }

    @PostMapping("/direct")
    public Result<CheckoutVO> direct(@Valid @RequestBody DirectCheckoutDTO dto, HttpServletRequest request) {
        return Result.success(checkoutService.checkoutDirect(SessionUser.requireId(request), dto));
    }

}
