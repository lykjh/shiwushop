package com.shiwushop.catalog.search;

import java.math.BigDecimal;

public record DiscoveryQuery(String q, Long categoryId, Long brandId, Long shopId,
                             BigDecimal minPrice, BigDecimal maxPrice, int size) { }
