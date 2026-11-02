package com.shiwushop.product.service;

import com.shiwushop.product.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.product.vo.product.ProductStatusVO;

/** 管理员商品状态操作与商家商品维护分离。 */
public interface AdminProductStatusService {
    ProductStatusVO adminTakeDown(Long productId, ProductStatusUpdateDTO dto);
}
