package com.shiwushop.product.service;

import com.shiwushop.product.dto.review.ReviewCreateDTO;
import com.shiwushop.product.dto.review.ReviewFollowUpDTO;
import com.shiwushop.product.dto.review.ReviewReportDTO;
import com.shiwushop.product.dto.review.ReviewReportDecisionDTO;
import com.shiwushop.product.vo.review.PendingReviewReportPageVO;

public interface ReviewService {
    /** 全额退款后在评价锁下扣减一次评分汇总，与退款状态共用事务。 */
    void excludeFullyRefunded(Long orderItemId, long refundedQuantity, int orderedQuantity);
    /** 买家对符合条件的已购订单项创建评价。 */
    Long create(Long buyerId, Long orderId, Long orderItemId, ReviewCreateDTO request);
    /** 评价作者在评价仍有效且未追评、订单项未全额退款时追加内容。 */
    void followUp(Long buyerId, Long reviewId, ReviewFollowUpDTO request);
    /** 评价作者删除自己的评价。 */
    void delete(Long buyerId, Long reviewId);
    /** 用户举报一条评价，避免重复提交相同举报。 */
    void report(Long reporterId, Long reviewId, ReviewReportDTO request);
    /** 管理员按游标查询待处理举报。 */
    PendingReviewReportPageVO pendingReports(Long afterId, int size);
    /** 管理员处理举报并更新相关评价状态。 */
    void resolveReport(Long adminId, Long reportId, ReviewReportDecisionDTO request);
}
