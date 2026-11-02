package com.shiwushop.product.search;

import com.shiwushop.product.vo.product.PublicProductVO;
import java.util.List;

public record DiscoveryPage(List<PublicProductVO> items, boolean hasMore, String nextCursor) { }
