package com.shiwushop.catalog.vo.review;

import java.time.LocalDateTime;

public record PendingReviewReportVO(Long id, Long reviewId, Integer rating, String content,
                                    String followUp, String reason, LocalDateTime createdAt) {
}
