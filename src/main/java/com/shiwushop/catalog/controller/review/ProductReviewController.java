package com.shiwushop.catalog.controller.review;

import com.shiwushop.catalog.vo.review.PublicReviewPageVO;
import com.shiwushop.catalog.vo.review.ReviewSummaryVO;
import com.shiwushop.common.Result;
import com.shiwushop.catalog.service.PublicReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ProductReviewController {
    private final PublicReviewService reviews;

    @GetMapping("/products/{productId}/reviews")
    public Result<PublicReviewPageVO> list(@PathVariable Long productId,
                                           @RequestParam(required = false) Long afterId,
                                           @RequestParam(defaultValue = "20") int size) {
        return Result.success(reviews.listPublic(productId, afterId, size));
    }

    @GetMapping("/products/{productId}/review-summary")
    public Result<ReviewSummaryVO> summary(@PathVariable Long productId) {
        return Result.success(reviews.summary(productId));
    }
}
