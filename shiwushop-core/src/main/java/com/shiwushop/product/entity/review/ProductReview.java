package com.shiwushop.product.entity.review;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProductReview {
    /** 商品评价 ID。 */
    private Long id;
    /** 关联订单明细 ID。 */
    private Long orderItemId;
    /** 商品（SPU）ID。 */
    private Long productId;
    /** 购买者用户 ID。 */
    private Long buyerUserId;
    /** 商品评价星级。 */
    private Integer rating;
    /** 评价正文。 */
    private String content;
    /** 追加评价内容。 */
    private String followUp;
    /** 追加评价时间。 */
    private LocalDateTime followUpAt;
    /** 记录软删除时间。 */
    private LocalDateTime deletedAt;
    /** 评价被隐藏的时间。 */
    private LocalDateTime hiddenAt;
    /** 该评价是否已计入商品评分汇总。 */
    private Boolean countedInSummary;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
}
