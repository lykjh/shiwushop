package com.shiwushop.product.vo.query.review;

import lombok.Data;

@Data
public class ReviewReportRow {
    /** 当前记录 ID。 */
    private Long id;
    /** 关联商品评价 ID。 */
    private Long reviewId;
    /** 评价举报状态：OPEN 待处理、RESOLVED 已处理。 */
    private String status;
}
