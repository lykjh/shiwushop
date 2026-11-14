package com.shiwushop.product.vo.query.review;

import lombok.Data;

@Data
public class ReviewImageRow {
    /** 关联商品评价 ID。 */
    private Long reviewId;
    /** 媒体资源 ID。 */
    private Long mediaId;
    /** 评价图片阶段：ORIGINAL 首评、FOLLOW_UP 追评。 */
    private String phase;
}
