package com.shiwushop.product.service;

import com.shiwushop.product.dto.inventory.InventoryMovementQuery;
import com.shiwushop.product.vo.inventory.InventoryMovementPageVO;

public interface InventoryMovementService {
    InventoryMovementPageVO list(Long userId, Long shopId, Long skuId, InventoryMovementQuery query);
}
