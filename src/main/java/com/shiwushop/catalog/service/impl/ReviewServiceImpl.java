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
import com.shiwushop.common.logging.BusinessEventLog;
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

    /**
     * 锁定订单项，确认买家已付款、已确认收货、未全额退款且尚未评价。
     * 写入评价、图片和评分汇总后，在同一事务加入商品搜索刷新事件。
     */
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
        BusinessEventLog.committed("catalog", "review_created", "review", review.getId());
        return review.getId();
    }

    /**
     * 对本人未删除、未隐藏且未追评的评价追加内容；全额退款的订单项不能追评。
     * 新图片也必须属于当前买家且已准备就绪。
     */
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
        BusinessEventLog.committed("catalog", "review_followed_up", "review", reviewId);
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
            BusinessEventLog.committed("catalog", "review_deleted", "review", reviewId);
        }
    }

    /** 举报公开可见的他人评价；锁定评价后写入举报原因。 */
    @Transactional
    public void report(Long reporterId, Long reviewId, ReviewReportDTO request) {
        accounts.checkActive(reporterId);
        String reason = reportReason(request == null ? null : request.reason());
        if (reviewId == null || reviewId <= 0) throw new BusinessException(400, "评价ID无效");
        ProductReview review = reviews.selectForUpdate(reviewId);
        if (review == null || !reviews.isPublicReview(reviewId)) throw new BusinessException(404, "评价不存在");
        if (reporterId.equals(review.getBuyerUserId())) throw new BusinessException(409, "不能举报自己的评价");
        reviews.insertReport(reviewId, reporterId, reason);
        BusinessEventLog.committed("catalog", "review_reported", "review", reviewId);
    }

    /** 用评价举报 ID 游标返回待处理列表，多读一条判断是否还有下一页。 */
    @Transactional(readOnly = true)
    public PendingReviewReportPageVO pendingReports(Long afterId, int size) {
        if (afterId != null && afterId <= 0) throw new BusinessException(400, "游标无效");
        PageSpec.of(1, size);
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
        BusinessEventLog.committed("catalog", "review_report_resolved", "report", reportId);
    }

    /** 锁定评价并确认作者身份；无权限时用 404 隐藏评价存在性。 */
    private ProductReview ownedForUpdate(Long buyerId, Long reviewId) {
        if (reviewId == null || reviewId <= 0) throw new BusinessException(400, "评价ID无效");
        ProductReview review = reviews.selectForUpdate(reviewId);
        if (review == null || !buyerId.equals(review.getBuyerUserId()))
            throw new BusinessException(404, "评价不存在");
        return review;
    }

    /** 去除评价正文首尾空白，并将长度限制在 1 到 2000 个 Unicode 字符。 */
    private String content(String value) {
        String content = value == null ? "" : value.strip();
        if (content.isEmpty() || content.codePointCount(0, content.length()) > 2000)
            throw new BusinessException(400, "评价内容须为1到2000字");
        return content;
    }

    /** 校验评价图片 ID 为正数、无重复且最多 9 张。 */
    private List<Long> images(List<Long> value) {
        List<Long> images = value == null ? List.of() : value;
        if (images.size() > 9 || images.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(images).size() != images.size())
            throw new BusinessException(400, "评价图片不能超过9张或重复");
        return images;
    }

    /** 清理举报或审核原因，并限制为 1 到 500 个 Unicode 字符。 */
    private String reportReason(String value) {
        String reason = value == null ? "" : value.strip();
        if (reason.isEmpty() || reason.codePointCount(0, reason.length()) > 500)
            throw new BusinessException(400, "原因须为1到500字");
        return reason;
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
