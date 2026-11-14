package com.shiwushop.product.entity.status;

public enum ProductStatus {
    /** 商品草稿，尚未提交上架审核。 */
    DRAFT,
    /** 商品已提交上架审核。 */
    PENDING_REVIEW,
    /** 商品上架申请被驳回。 */
    REJECTED,
    /** 商品已上架。 */
    ON_SALE,
    /** 商品已下架。 */
    OFF_SALE,
    /** 商品已归档。 */
    ARCHIVED
}
