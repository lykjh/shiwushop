package com.shiwushop.commerce.controller.cart;

import com.shiwushop.commerce.dto.cart.CartAddDTO;
import com.shiwushop.commerce.dto.cart.CartQuantityDTO;
import com.shiwushop.commerce.vo.cart.CartItemVO;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.commerce.service.CartService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/cart/items")
@RequiredArgsConstructor
public class CartController {
    private final CartService cartService;

    @PostMapping
    public Result<CartItemVO> add(@Valid @RequestBody CartAddDTO dto, HttpServletRequest request) {
        return Result.success(cartService.add(SessionUser.currentId(request), dto));
    }

    @GetMapping
    public Result<List<CartItemVO>> list(HttpServletRequest request) {
        return Result.success(cartService.list(SessionUser.currentId(request)));
    }

    @PutMapping("/{id}")
    public Result<CartItemVO> update(@PathVariable Long id, @Valid @RequestBody CartQuantityDTO dto,
                                     HttpServletRequest request) {
        return Result.success(cartService.updateQuantity(SessionUser.currentId(request), id, dto.getQuantity()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        cartService.delete(SessionUser.currentId(request), id);
        return Result.success();
    }

}
