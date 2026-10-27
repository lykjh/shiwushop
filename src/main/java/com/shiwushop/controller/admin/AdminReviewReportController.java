package com.shiwushop.controller.admin;

import com.shiwushop.dto.review.ReviewReportDecisionDTO;
import com.shiwushop.vo.review.PendingReviewReportPageVO;
import com.shiwushop.common.Result;
import com.shiwushop.service.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/review-reports")
@RequiredArgsConstructor
public class AdminReviewReportController {
    private final AdminAuth auth;
    private final ReviewService reviews;

    @GetMapping
    public Result<PendingReviewReportPageVO> pending(@RequestParam(required = false) Long afterId,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       HttpServletRequest http) {
        auth.requireAdmin(http);
        return Result.success(reviews.pendingReports(afterId, size));
    }

    @PostMapping("/{reportId}/resolve")
    public Result<Void> resolve(@PathVariable Long reportId, @RequestBody ReviewReportDecisionDTO request,
                                HttpServletRequest http) {
        reviews.resolveReport(auth.requireAdmin(http), reportId, request);
        return Result.success();
    }
}
