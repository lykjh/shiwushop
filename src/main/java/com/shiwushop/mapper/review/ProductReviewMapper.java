package com.shiwushop.mapper.review;

import com.shiwushop.entity.review.ProductReview;
import com.shiwushop.vo.review.PendingReviewReportVO;
import com.shiwushop.vo.review.ReviewSummaryVO;
import com.shiwushop.vo.query.review.ReviewEligibility;
import com.shiwushop.vo.query.review.ReviewImageRow;
import com.shiwushop.vo.query.review.ReviewReportRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ProductReviewMapper {
    ReviewEligibility selectEligibilityForUpdate(@Param("orderItemId") Long orderItemId);
    Long selectIdByOrderItemId(@Param("orderItemId") Long orderItemId);
    int insertReview(ProductReview review);
    int insertImage(@Param("reviewId") Long reviewId, @Param("mediaId") Long mediaId,
                    @Param("phase") String phase, @Param("position") int position);
    ProductReview selectForUpdate(@Param("reviewId") Long reviewId);
    ProductReview selectForUpdateByOrderItemId(@Param("orderItemId") Long orderItemId);
    long refundedQuantityForUpdate(@Param("orderItemId") Long orderItemId);
    int excludeFromSummary(@Param("reviewId") Long reviewId);
    int addToSummary(@Param("productId") Long productId, @Param("rating") int rating);
    int removeFromSummary(@Param("productId") Long productId, @Param("rating") int rating);
    int addFollowUp(@Param("reviewId") Long reviewId, @Param("content") String content);
    int softDelete(@Param("reviewId") Long reviewId);
    boolean isPublicReview(@Param("reviewId") Long reviewId);
    int insertReport(@Param("reviewId") Long reviewId, @Param("reporterId") Long reporterId,
                     @Param("reason") String reason);
    List<PendingReviewReportVO> selectPendingReports(@Param("afterId") Long afterId, @Param("limit") int limit);
    ReviewReportRow selectReport(@Param("reportId") Long reportId);
    ReviewReportRow selectReportForUpdate(@Param("reportId") Long reportId);
    int resolveReport(@Param("reportId") Long reportId, @Param("adminId") Long adminId,
                      @Param("decision") String decision);
    int hideReview(@Param("reviewId") Long reviewId);
    int insertModerationAudit(@Param("reviewId") Long reviewId, @Param("reportId") Long reportId,
                              @Param("adminId") Long adminId, @Param("decision") String decision,
                              @Param("reason") String reason);
    boolean isPublicProduct(@Param("productId") Long productId);
    List<ProductReview> selectPublicPage(@Param("productId") Long productId,
                                         @Param("afterId") Long afterId, @Param("limit") int limit);
    List<ReviewImageRow> selectPublicImages(@Param("reviewIds") List<Long> reviewIds);
    ReviewSummaryVO selectSummary(@Param("productId") Long productId);
}
