package com.shiwushop.catalog.search;

import com.shiwushop.vo.product.PublicProductVO;
import java.util.List;

public record DiscoveryPage(List<PublicProductVO> items, boolean hasMore, String nextCursor) { }
