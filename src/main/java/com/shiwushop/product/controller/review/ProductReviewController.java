package com.shiwushop.product.controller.review;

import jakarta.validation.constraints.Positive;
import com.shiwushop.product.vo.review.PublicReviewPageVO;
import com.shiwushop.product.vo.review.ReviewSummaryVO;
import com.shiwushop.common.Result;
import com.shiwushop.product.service.PublicReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ProductReviewController {
    private final PublicReviewService reviews;

    /** 按评价 ID 游标读取某商品对外可见的评价列表。 */
    @GetMapping("/products/{productId}/reviews")
    public Result<PublicReviewPageVO> list(@PathVariable @Positive Long productId,
                                           @RequestParam(required = false) @Positive Long afterId,
                                           @RequestParam(defaultValue = "20") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int size) {
        return Result.success(reviews.listPublic(productId, afterId, size));
    }

    /** 返回某商品的公开评价数量与评分汇总。 */
    @GetMapping("/products/{productId}/review-summary")
    public Result<ReviewSummaryVO> summary(@PathVariable @Positive Long productId) {
        return Result.success(reviews.summary(productId));
    }
}
