package com.shiwushop.vo.review;

import java.math.BigDecimal;

public record ReviewSummaryVO(long reviewCount, BigDecimal averageRating) {
}
