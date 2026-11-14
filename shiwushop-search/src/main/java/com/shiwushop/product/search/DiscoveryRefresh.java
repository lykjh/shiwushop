package com.shiwushop.product.search;

import com.shiwushop.product.vo.product.PublicProductVO;

import java.util.List;

public record DiscoveryRefresh(
    /** 当前对象的明细列表。 */
    List<PublicProductVO> items,
    /** 刷新时已失效或不再公开展示的商品 ID 列表。 */
    List<Long> invalidIds
) {
}
