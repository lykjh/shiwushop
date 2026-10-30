package com.shiwushop.catalog.service.impl;

import com.shiwushop.catalog.dto.review.ReviewCreateDTO;
import com.shiwushop.catalog.dto.review.ReviewFollowUpDTO;
import com.shiwushop.catalog.dto.review.ReviewReportDTO;
import com.shiwushop.catalog.dto.review.ReviewReportDecisionDTO;
import com.shiwushop.catalog.vo.review.PendingReviewReportPageVO;
import com.shiwushop.catalog.vo.review.PendingReviewReportVO;
import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.media.MediaAssetAccess;
import com.shiwushop.common.pagination.PageSpec;
import com.shiwushop.catalog.entity.review.ProductReview;
import com.shiwushop.catalog.mapper.review.ProductReviewMapper;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.catalog.vo.query.review.ReviewEligibility;
import com.shiwushop.catalog.vo.query.review.ReviewReportRow;
import com.shiwushop.catalog.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {
    private final AccountAccess accounts;
    private final ProductReviewMapper reviews;
    private final MediaAssetAccess media;
    private final OutboxEventMapper outbox;

    @Transactional
    public Long create(Long buyerId, Long orderId, Long orderItemId, ReviewCreateDTO request) {
        accounts.checkActive(buyerId);
        if (orderId == null || orderId <= 0 || orderItemId == null || orderItemId <= 0)
            throw new BusinessException(400, "订单或明细ID无效");
        if (request == null || request.rating() == null || request.rating() < 1 || request.rating() > 5)
            throw new BusinessException(400, "评分必须为1到5分");
        String content = content(request.content());
        List<Long> images = images(request.imageMediaIds());

        // 以订单明细为唯一评价资格，并锁定该明细以串行化并发创建。
        ReviewEligibility eligibility = reviews.selectEligibilityForUpdate(orderItemId);
        if (eligibility == null || !orderId.equals(eligibility.getOrderId())
                || !buyerId.equals(eligibility.getBuyerUserId()))
            throw new BusinessException(404, "订单商品不存在");
        if (!"PAID".equals(eligibility.getOrderStatus())
                || !"CONFIRMED".equals(eligibility.getShipmentStatus()))
            throw new BusinessException(409, "尚未确认收货，不能评价");
        if (eligibility.getQuantity() <= eligibility.getRefundedQuantity())
            throw new BusinessException(409, "该明细已全部退款，不能评价");
        if (reviews.selectIdByOrderItemId(orderItemId) != null)
            throw new BusinessException(409, "该订单商品已评价");
        for (Long image : images) media.requireReadyOwned(image, buyerId, "评价图片无效或不属于当前用户");

        ProductReview review = new ProductReview();
        review.setOrderItemId(orderItemId);
        review.setProductId(eligibility.getProductId());
        review.setBuyerUserId(buyerId);
        review.setRating(request.rating());
        review.setContent(content);
        review.setCountedInSummary(true);
        reviews.insertReview(review);
        if (reviews.addToSummary(review.getProductId(), review.getRating()) < 1)
            throw new IllegalStateException("评价汇总写入失败");
        for (int i = 0; i < images.size(); i++) reviews.insertImage(review.getId(), images.get(i), "ORIGINAL", i);
        refreshSearch(eligibility.getProductId());
        return review.getId();
    }

    @Transactional
    public void followUp(Long buyerId, Long reviewId, ReviewFollowUpDTO request) {
        accounts.checkActive(buyerId);
        if (request == null) throw new BusinessException(400, "追评内容不能为空");
        String content = content(request.content());
        List<Long> images = images(request.imageMediaIds());
        ProductReview review = ownedForUpdate(buyerId, reviewId);
        if (review.getDeletedAt() != null || review.getHiddenAt() != null || review.getFollowUpAt() != null)
            throw new BusinessException(409, "评价不可追评或已追评");
        ReviewEligibility eligibility = reviews.selectEligibilityForUpdate(review.getOrderItemId());
        if (eligibility.getQuantity() <= eligibility.getRefundedQuantity())
            throw new BusinessException(409, "该明细已全部退款，不能追评");
        for (Long image : images) media.requireReadyOwned(image, buyerId, "追评图片无效或不属于当前用户");
        if (reviews.addFollowUp(reviewId, content) != 1) throw new BusinessException(409, "评价不可追评或已追评");
        for (int i = 0; i < images.size(); i++) reviews.insertImage(reviewId, images.get(i), "FOLLOW_UP", i);
    }

    @Transactional
    public void delete(Long buyerId, Long reviewId) {
        accounts.checkActive(buyerId);
        ProductReview review = ownedForUpdate(buyerId, reviewId);
        // 软删除保留“一条明细最多评价一次”的约束，同时从公开评分中移除。
        if (review.getDeletedAt() == null) {
            if (reviews.softDelete(reviewId) != 1) throw new IllegalStateException("评价删除失败");
            if (Boolean.TRUE.equals(review.getCountedInSummary())) removeFromSummary(review);
            refreshSearch(review.getProductId());
        }
    }

    @Transactional
    public void report(Long reporterId, Long reviewId, ReviewReportDTO request) {
        accounts.checkActive(reporterId);
        String reason = reportReason(request == null ? null : request.reason());
        if (reviewId == null || reviewId <= 0) throw new BusinessException(400, "评价ID无效");
        ProductReview review = reviews.selectForUpdate(reviewId);
        if (review == null || !reviews.isPublicReview(reviewId)) throw new BusinessException(404, "评价不存在");
        if (reporterId.equals(review.getBuyerUserId())) throw new BusinessException(409, "不能举报自己的评价");
        reviews.insertReport(reviewId, reporterId, reason);
    }

    @Transactional(readOnly = true)
    public PendingReviewReportPageVO pendingReports(Long afterId, int size) {
        if (afterId != null && afterId <= 0) throw new BusinessException(400, "游标无效");
        PageSpec.of(1, size);
        List<PendingReviewReportVO> rows = reviews.selectPendingReports(afterId, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) rows = rows.subList(0, size);
        return new PendingReviewReportPageVO(rows, hasMore, hasMore ? rows.getLast().id() : null);
    }

    @Transactional
    public void resolveReport(Long adminId, Long reportId, ReviewReportDecisionDTO request) {
        if (reportId == null || reportId <= 0) throw new BusinessException(400, "举报ID无效");
        String decision = request == null ? null : request.decision();
        if (!"KEEP".equals(decision) && !"HIDE".equals(decision))
            throw new BusinessException(400, "处理决定只能是KEEP或HIDE");
        String reason = reportReason(request.reason());
        ReviewReportRow report = reviews.selectReport(reportId);
        if (report == null) throw new BusinessException(404, "举报不存在");
        // 先锁评价再锁举报，统一加锁顺序以避免并发处理时交叉等待。
        ProductReview review = reviews.selectForUpdate(report.getReviewId());
        report = reviews.selectReportForUpdate(reportId);
        if (!"OPEN".equals(report.getStatus())) throw new BusinessException(409, "举报已处理");
        if ("HIDE".equals(decision)) {
            if (reviews.hideReview(report.getReviewId()) != 1)
                throw new IllegalStateException("评价隐藏失败");
            if (Boolean.TRUE.equals(review.getCountedInSummary())) removeFromSummary(review);
            refreshSearch(review.getProductId());
        }
        if (reviews.resolveReport(reportId, adminId, decision) != 1)
            throw new BusinessException(409, "举报已处理");
        reviews.insertModerationAudit(report.getReviewId(), reportId, adminId, decision, reason);
    }

    private ProductReview ownedForUpdate(Long buyerId, Long reviewId) {
        if (reviewId == null || reviewId <= 0) throw new BusinessException(400, "评价ID无效");
        ProductReview review = reviews.selectForUpdate(reviewId);
        if (review == null || !buyerId.equals(review.getBuyerUserId()))
            throw new BusinessException(404, "评价不存在");
        return review;
    }

    private String content(String value) {
        String content = value == null ? "" : value.strip();
        if (content.isEmpty() || content.codePointCount(0, content.length()) > 2000)
            throw new BusinessException(400, "评价内容须为1到2000字");
        return content;
    }

    private List<Long> images(List<Long> value) {
        List<Long> images = value == null ? List.of() : value;
        if (images.size() > 9 || images.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(images).size() != images.size())
            throw new BusinessException(400, "评价图片不能超过9张或重复");
        return images;
    }

    private String reportReason(String value) {
        String reason = value == null ? "" : value.strip();
        if (reason.isEmpty() || reason.codePointCount(0, reason.length()) > 500)
            throw new BusinessException(400, "原因须为1到500字");
        return reason;
    }

    private void refreshSearch(Long productId) {
        if (outbox.insertSearchProductRefresh(productId) != 1)
            throw new IllegalStateException("评价商品搜索事件写入失败");
    }

    private void removeFromSummary(ProductReview review) {
        if (reviews.removeFromSummary(review.getProductId(), review.getRating()) != 1)
            throw new IllegalStateException("评价汇总与评价状态不一致");
    }
}
