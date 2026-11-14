package com.shiwushop.product.vo.review;

import java.time.LocalDateTime;
import java.util.List;

public record PublicReviewVO(
    /** 当前记录 ID。 */
    Long id,
    /** 商品评价星级。 */
    Integer rating,
    /** 评价正文。 */
    String content,
    /** 追加评价内容。 */
    String followUp,
    /** 记录创建时间。 */
    LocalDateTime createdAt,
    /** 追加评价时间。 */
    LocalDateTime followUpAt,
    /** 图片媒体 ID 列表。 */
    List<Long> imageMediaIds,
    /** 追评图片媒体 ID 列表。 */
    List<Long> followUpImageMediaIds
) {
}
