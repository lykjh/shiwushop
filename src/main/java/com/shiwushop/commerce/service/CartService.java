package com.shiwushop.commerce.service;

import com.shiwushop.commerce.dto.cart.CartAddDTO;
import com.shiwushop.commerce.vo.cart.CartItemVO;


import java.util.List;

public interface CartService {
    CartItemVO add(Long userId, CartAddDTO dto);
    List<CartItemVO> list(Long userId);
    CartItemVO updateQuantity(Long userId, Long id, Integer quantity);
    void delete(Long userId, Long id);
}
