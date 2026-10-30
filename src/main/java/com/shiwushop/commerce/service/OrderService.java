package com.shiwushop.commerce.service;

import com.shiwushop.commerce.vo.order.OrderSliceVO;
import com.shiwushop.commerce.vo.order.OrderDetailVO;



public interface OrderService {
    /** 查询买家本人订单的商品、金额、支付和物流详情。 */
    OrderDetailVO detail(Long userId, Long orderId);

    /** 按 ID 游标列出买家订单。 */
    OrderSliceVO list(Long userId, Long afterId, int size);

    /** 按 ID 游标列出指定店铺的商家订单。 */
    OrderSliceVO listMerchant(Long userId, Long shopId, Long afterId, int size);

    /** 查询商家所属店铺的一笔订单详情。 */
    OrderDetailVO detailMerchant(Long userId, Long shopId, Long orderId);
}
