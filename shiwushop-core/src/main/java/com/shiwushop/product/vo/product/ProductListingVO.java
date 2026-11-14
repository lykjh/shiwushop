package com.shiwushop.product.vo.product;

public record ProductListingVO(
    /** 商品（SPU）ID。 */
    Long productId,
    /** 商品内容修订号，用于上架审批和并发校验。 */
    Long revision,
    /** 上架申请状态：PENDING 待审、APPROVED 通过、REJECTED 驳回。 */
    String status,
    /** 本次业务操作的原因。 */
    String reason
) {
}
