package com.shiwushop.product.vo.review;

import java.util.List;

public record PublicReviewPageVO(
    /** 当前对象的明细列表。 */
    List<PublicReviewVO> items,
    /** 是否还有下一页。 */
    boolean hasMore,
    /** 下一页查询游标。 */
    Long nextCursor
) {
}
