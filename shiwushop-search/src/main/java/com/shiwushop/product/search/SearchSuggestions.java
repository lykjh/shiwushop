package com.shiwushop.product.search;

import java.util.List;

public record SearchSuggestions(
    /** 商品搜索建议。 */
    List<Entry> products,
    /** 店铺搜索建议。 */
    List<Entry> shops,
    /** 品牌搜索建议。 */
    List<Entry> brands
) {
    public record Entry(
        /** 搜索建议对应的商品、店铺或品牌 ID。 */
        Long id,
        /** 名称。 */
        String name,
        /** 搜索建议对应的跳转路径。 */
        String path
    ) {
    }
}
