package com.shiwushop.commerce.mapper.cart;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.commerce.entity.cart.CartItem;
import org.apache.ibatis.annotations.Param;

public interface CartItemMapper extends BaseMapper<CartItem> {
    CartItem selectOwnedForUpdate(@Param("userId") Long userId, @Param("id") Long id);
}
