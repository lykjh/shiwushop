package com.shiwushop.commerce.service;

import com.shiwushop.commerce.dto.cart.CartAddDTO;
import com.shiwushop.commerce.dto.cart.CheckoutDTO;
import com.shiwushop.commerce.dto.cart.DirectCheckoutDTO;
import com.shiwushop.commerce.vo.cart.CartItemVO;
import com.shiwushop.commerce.vo.order.CheckoutVO;


import java.util.List;

public interface CartService {
    CartItemVO add(Long userId, CartAddDTO dto);
    List<CartItemVO> list(Long userId);
    CartItemVO updateQuantity(Long userId, Long id, Integer quantity);
    void delete(Long userId, Long id);
    CheckoutVO checkoutSelected(Long userId, CheckoutDTO dto);
    CheckoutVO checkoutDirect(Long userId, DirectCheckoutDTO dto);
}
