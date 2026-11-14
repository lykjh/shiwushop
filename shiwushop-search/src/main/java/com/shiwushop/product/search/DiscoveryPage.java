package com.shiwushop.product.search;

import com.shiwushop.product.vo.product.PublicProductVO;

import java.util.List;

public record DiscoveryPage(
    /** 当前对象的明细列表。 */
    List<PublicProductVO> items,
    /** 是否还有下一页。 */
    boolean hasMore,
    /** 下一页查询游标。 */
    String nextCursor
) {
}
