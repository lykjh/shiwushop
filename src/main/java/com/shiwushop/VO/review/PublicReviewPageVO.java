package com.shiwushop.vo.review;

import java.util.List;

public record PublicReviewPageVO(List<PublicReviewVO> items, boolean hasMore, Long nextCursor) { }
