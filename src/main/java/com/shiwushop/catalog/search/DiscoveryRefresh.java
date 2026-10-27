package com.shiwushop.catalog.search;

import com.shiwushop.vo.product.PublicProductVO;
import java.util.List;

public record DiscoveryRefresh(List<PublicProductVO> items, List<Long> invalidIds) { }
