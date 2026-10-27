package com.shiwushop.controller.review;

import com.shiwushop.dto.review.ReviewReportDTO;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.service.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReviewReportController {
    private final ReviewService reviews;

    @PostMapping("/reviews/{reviewId}/reports")
    public Result<Void> report(@PathVariable Long reviewId, @RequestBody ReviewReportDTO request,
                               HttpServletRequest http) {
        reviews.report(SessionUser.requireId(http), reviewId, request);
        return Result.success();
    }
}
