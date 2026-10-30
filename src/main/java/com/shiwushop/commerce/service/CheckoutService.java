package com.shiwushop.commerce.service;

import com.shiwushop.commerce.dto.cart.CheckoutDTO;
import com.shiwushop.commerce.dto.cart.DirectCheckoutDTO;
import com.shiwushop.commerce.vo.order.CheckoutVO;

/** 结算独立于购物车维护；直接购买也走相同的订单事务。 */
public interface CheckoutService {
    /** 将当前用户选中的购物车项结算成订单，并更新库存。 */
    CheckoutVO checkoutSelected(Long userId, CheckoutDTO dto);
    /** 不经过购物车，直接将指定 SKU 结算成订单。 */
    CheckoutVO checkoutDirect(Long userId, DirectCheckoutDTO dto);
}
