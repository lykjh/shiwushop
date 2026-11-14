package com.shiwushop.product.vo.review;

import java.math.BigDecimal;

public record ReviewSummaryVO(
    /** 已纳入汇总的评价条数。 */
    long reviewCount,
    /** 商品平均评分。 */
    BigDecimal averageRating
) {
}
