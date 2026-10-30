package com.shiwushop.catalog.service;

import com.shiwushop.catalog.vo.review.PublicReviewPageVO;
import com.shiwushop.catalog.vo.review.ReviewSummaryVO;

/** 公开评价查询与买家写入、举报处理分开，避免只读入口依赖写入流程。 */
public interface PublicReviewService {
    /** 按商品与评价 ID 游标读取公开可见的评价。 */
    PublicReviewPageVO listPublic(Long productId, Long afterId, int size);
    /** 汇总商品可见评价数量和评分。 */
    ReviewSummaryVO summary(Long productId);
}
