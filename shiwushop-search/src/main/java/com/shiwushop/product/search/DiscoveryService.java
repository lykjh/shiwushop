package com.shiwushop.product.search;

import java.util.List;

/** 提供商品发现分页、已有商品卡片刷新和搜索建议。 */
public interface DiscoveryService {
    DiscoveryPage list(DiscoveryQuery raw, String cursor, Long userId);

    DiscoveryRefresh refresh(List<Long> ids, DiscoveryQuery raw);

    SearchSuggestions suggestions(String raw);
}
