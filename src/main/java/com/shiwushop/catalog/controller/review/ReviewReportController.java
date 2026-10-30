package com.shiwushop.catalog.controller.review;

import com.shiwushop.catalog.dto.review.ReviewReportDTO;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.catalog.service.ReviewService;
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

    /** 提交对指定评价的举报；服务层处理举报人、评价和重复举报规则。 */
    @PostMapping("/reviews/{reviewId}/reports")
    public Result<Void> report(@PathVariable Long reviewId, @RequestBody ReviewReportDTO request,
                               HttpServletRequest http) {
        reviews.report(SessionUser.requireId(http), reviewId, request);
        return Result.success();
    }
}
