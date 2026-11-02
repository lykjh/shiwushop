package com.shiwushop.product.service.impl;

import com.shiwushop.product.dto.review.ReviewCreateDTO;
import com.shiwushop.product.dto.review.ReviewFollowUpDTO;
import com.shiwushop.product.dto.review.ReviewReportDTO;
import com.shiwushop.product.dto.review.ReviewReportDecisionDTO;
import com.shiwushop.product.vo.review.PendingReviewReportPageVO;
import com.shiwushop.product.vo.review.PendingReviewReportVO;
import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.media.MediaAssetAccess;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.product.entity.review.ProductReview;
import com.shiwushop.product.mapper.review.ProductReviewMapper;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.product.vo.query.review.ReviewEligibility;
import com.shiwushop.product.vo.query.review.ReviewReportRow;
import com.shiwushop.product.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {
    @Override
    @Transactional
    public void excludeFullyRefunded(Long orderItemId, long refundedQuantity, int orderedQuantity) {
        ProductReview review = reviews.selectForUpdateByOrderItemId(orderItemId);
        if (review == null || !Boolean.TRUE.equals(review.getCountedInSummary())) return;
        if (refundedQuantity >= orderedQuantity) {
            if (reviews.excludeFromSummary(review.getId()) != 1
                    || reviews.removeFromSummary(review.getProductId(), review.getRating()) != 1)
                throw new IllegalStateException("退款后评价汇总更新失败");
        }
    }
    private final AccountAccess accounts;
    private final ProductReviewMapper reviews;
    private final MediaAssetAccess media;
    private final OutboxEventMapper outbox;

    /**
     * 锁定订单项，确认买家已付款、已确认收货、未全额退款且尚未评价。
     * 写入评价、图片和评分汇总后，在同一事务加入商品搜索刷新事件。
     */
    @Transactional
    public Long create(Long buyerId, Long orderId, Long orderItemId, ReviewCreateDTO request) {
        accounts.checkActive(buyerId);
        String content = request.content();
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
        BusinessEventLog.committed("product", "review_created", "review", review.getId());
        return review.getId();
    }

    /**
     * 对本人未删除、未隐藏且未追评的评价追加内容；全额退款的订单项不能追评。
     * 新图片也必须属于当前买家且已准备就绪。
     */
    @Transactional
    public void followUp(Long buyerId, Long reviewId, ReviewFollowUpDTO request) {
        accounts.checkActive(buyerId);
        String content = request.content();
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
        BusinessEventLog.committed("product", "review_followed_up", "review", reviewId);
    }

    /** 软删除本人评价；被计入公开评分时同步扣减汇总，并刷新搜索结果。 */
    @Transactional
    public void delete(Long buyerId, Long reviewId) {
        accounts.checkActive(buyerId);
        ProductReview review = ownedForUpdate(buyerId, reviewId);
        // 软删除保留“一条明细最多评价一次”的约束，同时从公开评分中移除。
        if (review.getDeletedAt() == null) {
            if (reviews.softDelete(reviewId) != 1) throw new IllegalStateException("评价删除失败");
            if (Boolean.TRUE.equals(review.getCountedInSummary())) removeFromSummary(review);
            refreshSearch(review.getProductId());
            BusinessEventLog.committed("product", "review_deleted", "review", reviewId);
        }
    }

    /** 举报公开可见的他人评价；锁定评价后写入举报原因。 */
    @Transactional
    public void report(Long reporterId, Long reviewId, ReviewReportDTO request) {
        accounts.checkActive(reporterId);
        String reason = request.reason();
        ProductReview review = reviews.selectForUpdate(reviewId);
        if (review == null || !reviews.isPublicReview(reviewId)) throw new BusinessException(404, "评价不存在");
        if (reporterId.equals(review.getBuyerUserId())) throw new BusinessException(409, "不能举报自己的评价");
        reviews.insertReport(reviewId, reporterId, reason);
        BusinessEventLog.committed("product", "review_reported", "review", reviewId);
    }

    /** 用评价举报 ID 游标返回待处理列表，多读一条判断是否还有下一页。 */
    @Transactional(readOnly = true)
    public PendingReviewReportPageVO pendingReports(Long afterId, int size) {
        List<PendingReviewReportVO> rows = reviews.selectPendingReports(afterId, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) rows = rows.subList(0, size);
        return new PendingReviewReportPageVO(rows, hasMore, hasMore ? rows.getLast().id() : null);
    }

    /**
     * 管理员选择保留或隐藏评价。先锁评价再锁举报，避免并发处理交叉等待；
     * 隐藏时移出评分汇总并刷新搜索，最后写处理审计记录。
     */
    @Transactional
    public void resolveReport(Long adminId, Long reportId, ReviewReportDecisionDTO request) {
        String decision = request.decision();
        String reason = request.reason();
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
        BusinessEventLog.committed("product", "review_report_resolved", "report", reportId);
    }

    /** 锁定评价并确认作者身份；无权限时用 404 隐藏评价存在性。 */
    private ProductReview ownedForUpdate(Long buyerId, Long reviewId) {
        ProductReview review = reviews.selectForUpdate(reviewId);
        if (review == null || !buyerId.equals(review.getBuyerUserId()))
            throw new BusinessException(404, "评价不存在");
        return review;
    }

    /** 检查图片关联不重复；格式和数量已由入口校验。 */
    private List<Long> images(List<Long> value) {
        List<Long> images = value == null ? List.of() : value;
        if (new HashSet<>(images).size() != images.size())
            throw new BusinessException(400, "评价图片不能超过9张或重复");
        return images;
    }

    /** 把评价变化写入商品搜索刷新发件箱；失败时让当前事务回滚。 */
    private void refreshSearch(Long productId) {
        if (outbox.insertSearchProductRefresh(productId) != 1)
            throw new IllegalStateException("评价商品搜索事件写入失败");
    }

    /** 从商品评分汇总扣除一条评价；更新不到汇总时报告数据不一致。 */
    private void removeFromSummary(ProductReview review) {
        if (reviews.removeFromSummary(review.getProductId(), review.getRating()) != 1)
            throw new IllegalStateException("评价汇总与评价状态不一致");
    }
}
