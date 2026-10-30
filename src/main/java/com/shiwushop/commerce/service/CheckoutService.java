package com.shiwushop.commerce.service;

import com.shiwushop.commerce.dto.cart.CheckoutDTO;
import com.shiwushop.commerce.dto.cart.DirectCheckoutDTO;
import com.shiwushop.commerce.vo.order.CheckoutVO;

/** 结算独立于购物车维护；直接购买也走相同的订单事务。 */
public interface CheckoutService {
    CheckoutVO checkoutSelected(Long userId, CheckoutDTO dto);
    CheckoutVO checkoutDirect(Long userId, DirectCheckoutDTO dto);
}
