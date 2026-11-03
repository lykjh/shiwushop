package com.shiwushop.product.vo.inventory;

import java.time.LocalDateTime;

public record InventoryMovementVO(Long id, String reason, int delta, int balanceAfter,
                                  Long orderItemId, LocalDateTime createdAt) {}
