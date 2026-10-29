package com.shiwushop.catalog.vo.review;

import java.util.List;

public record PendingReviewReportPageVO(List<PendingReviewReportVO> items, boolean hasMore, Long nextCursor) {
}
