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

    /** 将指定 SKU 和数量加入当前用户购物车；服务层检查商品可售状态。 */
    @PostMapping
    public Result<CartItemVO> add(@Valid @RequestBody CartAddDTO dto, HttpServletRequest request) {
        return Result.success(cartService.add(SessionUser.currentId(request), dto));
    }

    /** 返回当前用户的购物车项及其最新展示信息。 */
    @GetMapping
    public Result<List<CartItemVO>> list(HttpServletRequest request) {
        return Result.success(cartService.list(SessionUser.currentId(request)));
    }

    /** 修改本人购物车项的数量；服务层检查归属与数量约束。 */
    @PutMapping("/{id}")
    public Result<CartItemVO> update(@PathVariable Long id, @Valid @RequestBody CartQuantityDTO dto,
                                     HttpServletRequest request) {
        return Result.success(cartService.updateQuantity(SessionUser.currentId(request), id, dto.getQuantity()));
    }

    /** 删除本人购物车中的指定项。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        cartService.delete(SessionUser.currentId(request), id);
        return Result.success();
    }

}
