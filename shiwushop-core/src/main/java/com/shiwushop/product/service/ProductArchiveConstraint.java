package com.shiwushop.product.service;

public interface ProductArchiveConstraint {
    boolean hasUnfinishedProductCommitments(Long shopId, Long productId);
}
