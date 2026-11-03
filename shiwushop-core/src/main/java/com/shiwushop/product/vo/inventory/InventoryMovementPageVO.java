package com.shiwushop.product.vo.inventory;

import java.util.List;

public record InventoryMovementPageVO(List<InventoryMovementVO> items, boolean hasMore, Long nextCursor) {}
