package com.shiwushop.product.vo.product;

import java.util.List;

public record PublicProductPageVO(
    /** 当前对象的明细列表。 */
    List<PublicProductVO> items,
    /** 每页请求条数。 */
    int size,
    /** 是否还有下一页。 */
    boolean hasMore,
    /** 下一页查询游标。 */
    Long nextCursor
) {
}
