package com.shiwushop.catalog.service;

import com.shiwushop.catalog.dto.review.ReviewCreateDTO;
import com.shiwushop.catalog.dto.review.ReviewFollowUpDTO;
import com.shiwushop.catalog.dto.review.ReviewReportDTO;
import com.shiwushop.catalog.dto.review.ReviewReportDecisionDTO;
import com.shiwushop.catalog.vo.review.PendingReviewReportPageVO;
import com.shiwushop.catalog.vo.review.PublicReviewPageVO;
import com.shiwushop.catalog.vo.review.ReviewSummaryVO;

public interface ReviewService {
    Long create(Long buyerId, Long orderId, Long orderItemId, ReviewCreateDTO request);
    void followUp(Long buyerId, Long reviewId, ReviewFollowUpDTO request);
    void delete(Long buyerId, Long reviewId);
    void report(Long reporterId, Long reviewId, ReviewReportDTO request);
    PendingReviewReportPageVO pendingReports(Long afterId, int size);
    void resolveReport(Long adminId, Long reportId, ReviewReportDecisionDTO request);
    PublicReviewPageVO listPublic(Long productId, Long afterId, int size);
    ReviewSummaryVO summary(Long productId);
}
