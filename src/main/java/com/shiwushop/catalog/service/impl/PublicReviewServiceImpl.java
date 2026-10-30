package com.shiwushop.catalog.service.impl;

import com.shiwushop.catalog.entity.review.ProductReview;
import com.shiwushop.catalog.mapper.review.ProductReviewMapper;
import com.shiwushop.catalog.service.PublicReviewService;
import com.shiwushop.catalog.vo.query.review.ReviewImageRow;
import com.shiwushop.catalog.vo.review.PublicReviewPageVO;
import com.shiwushop.catalog.vo.review.PublicReviewVO;
import com.shiwushop.catalog.vo.review.ReviewSummaryVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.pagination.PageSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 公开评价及评分汇总只读；写入和审核仍由各自的服务负责。 */
@Service
@RequiredArgsConstructor
public class PublicReviewServiceImpl implements PublicReviewService {
    private final ProductReviewMapper reviews;

    /**
     * 确认商品公开可见后按评价 ID 游标取一页评价，多读一条判断下一页。
     * 图片一次批量查询，再按原评与追评分别归入返回结果。
     */
    @Override
    @Transactional(readOnly = true)
    public PublicReviewPageVO listPublic(Long productId, Long afterId, int size) {
        if (productId == null || productId <= 0 || afterId != null && afterId <= 0)
            throw new BusinessException(400, "商品ID或游标无效");
        PageSpec.of(1, size);
        if (!reviews.isPublicProduct(productId)) throw new BusinessException(404, "商品不存在");
        List<ProductReview> rows = reviews.selectPublicPage(productId, afterId, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) rows = rows.subList(0, size);
        if (rows.isEmpty()) return new PublicReviewPageVO(List.of(), false, null);
        Map<Long, List<ReviewImageRow>> images = reviews.selectPublicImages(rows.stream().map(ProductReview::getId).toList())
                .stream().collect(Collectors.groupingBy(ReviewImageRow::getReviewId));
        List<PublicReviewVO> items = rows.stream().map(row -> {
            List<ReviewImageRow> photos = images.getOrDefault(row.getId(), List.of());
            return new PublicReviewVO(row.getId(), row.getRating(), row.getContent(), row.getFollowUp(),
                    row.getCreatedAt(), row.getFollowUpAt(),
                    photos.stream().filter(image -> "ORIGINAL".equals(image.getPhase())).map(ReviewImageRow::getMediaId).toList(),
                    photos.stream().filter(image -> "FOLLOW_UP".equals(image.getPhase())).map(ReviewImageRow::getMediaId).toList());
        }).toList();
        return new PublicReviewPageVO(items, hasMore, hasMore ? items.getLast().id() : null);
    }

    /** 只为公开可见商品返回数据库维护的评价数量和评分汇总。 */
    @Override
    @Transactional(readOnly = true)
    public ReviewSummaryVO summary(Long productId) {
        if (productId == null || productId <= 0) throw new BusinessException(400, "商品ID无效");
        if (!reviews.isPublicProduct(productId)) throw new BusinessException(404, "商品不存在");
        return reviews.selectSummary(productId);
    }
}
