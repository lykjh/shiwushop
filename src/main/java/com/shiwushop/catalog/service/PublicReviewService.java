package com.shiwushop.catalog.service;

import com.shiwushop.catalog.vo.review.PublicReviewPageVO;
import com.shiwushop.catalog.vo.review.ReviewSummaryVO;

/** 公开评价查询与买家写入、举报处理分开，避免只读入口依赖写入流程。 */
public interface PublicReviewService {
    PublicReviewPageVO listPublic(Long productId, Long afterId, int size);
    ReviewSummaryVO summary(Long productId);
}
