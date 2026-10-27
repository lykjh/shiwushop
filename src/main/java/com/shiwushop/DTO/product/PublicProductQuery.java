package com.shiwushop.dto.product;

import java.math.BigDecimal;

public record PublicProductQuery(Long categoryId, Long brandId, Long shopId,
                                 BigDecimal minPrice, BigDecimal maxPrice,
                                 int size, Long afterId) { }
