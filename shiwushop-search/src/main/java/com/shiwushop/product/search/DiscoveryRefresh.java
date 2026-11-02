package com.shiwushop.product.search;

import com.shiwushop.product.vo.product.PublicProductVO;
import java.util.List;

public record DiscoveryRefresh(List<PublicProductVO> items, List<Long> invalidIds) { }
