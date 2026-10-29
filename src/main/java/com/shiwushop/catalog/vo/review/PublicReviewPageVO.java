package com.shiwushop.catalog.vo.review;

import java.util.List;

public record PublicReviewPageVO(List<PublicReviewVO> items, boolean hasMore, Long nextCursor) { }
