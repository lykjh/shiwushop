package com.shiwushop.product.vo.review;

import java.time.LocalDateTime;

public record PendingReviewReportVO(
    /** 当前记录 ID。 */
    Long id,
    /** 关联商品评价 ID。 */
    Long reviewId,
    /** 商品评价星级。 */
    Integer rating,
    /** 评价正文。 */
    String content,
    /** 追加评价内容。 */
    String followUp,
    /** 该举报记录的举报原因。 */
    String reason,
    /** 记录创建时间。 */
    LocalDateTime createdAt
) {
}
