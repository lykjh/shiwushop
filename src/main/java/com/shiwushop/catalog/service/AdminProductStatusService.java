package com.shiwushop.catalog.service;

import com.shiwushop.catalog.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.catalog.vo.product.ProductStatusVO;

/** 管理员商品状态操作与商家商品维护分离。 */
public interface AdminProductStatusService {
    ProductStatusVO adminTakeDown(Long productId, ProductStatusUpdateDTO dto);
}
