package com.shiwushop.product.vo.product;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ProductStatusVO {
    /** 当前记录 ID。 */
    private Long id;
    /** 商品状态，取值见 ProductStatus。 */
    private String status;
    /** 商品内容修订号，用于上架审批和并发校验。 */
    private Long revision;
    /** 商品上架申请审核状态。 */
    private String reviewStatus;

    public ProductStatusVO(Long id, String status) {
        this(id, status, null, null);
    }
}
