package com.shiwushop.service;

import com.shiwushop.dto.review.ReviewCreateDTO;
import com.shiwushop.dto.review.ReviewFollowUpDTO;
import com.shiwushop.dto.review.ReviewReportDTO;
import com.shiwushop.dto.review.ReviewReportDecisionDTO;
import com.shiwushop.vo.review.PendingReviewReportPageVO;
import com.shiwushop.vo.review.PublicReviewPageVO;
import com.shiwushop.vo.review.ReviewSummaryVO;

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
