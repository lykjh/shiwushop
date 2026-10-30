package com.shiwushop.commerce.service;

import com.shiwushop.commerce.dto.cart.CartAddDTO;
import com.shiwushop.commerce.vo.cart.CartItemVO;


import java.util.List;

public interface CartService {
    /** 添加一个 SKU 到当前用户购物车；重复添加返回冲突。 */
    CartItemVO add(Long userId, CartAddDTO dto);
    /** 查询当前用户购物车中的项目及最新商品信息。 */
    List<CartItemVO> list(Long userId);
    /** 修改本人购物车项目的购买数量。 */
    CartItemVO updateQuantity(Long userId, Long id, Integer quantity);
    /** 删除本人购物车项目。 */
    void delete(Long userId, Long id);
}
