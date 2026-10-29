package com.shiwushop.catalog.controller.review;

import com.shiwushop.catalog.dto.review.ReviewFollowUpDTO;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.catalog.service.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reviews/{reviewId}")
@RequiredArgsConstructor
public class ReviewOwnerController {
    private final ReviewService reviews;

    @PostMapping("/follow-up")
    public Result<Void> followUp(@PathVariable Long reviewId, @RequestBody ReviewFollowUpDTO request,
                                 HttpServletRequest http) {
        reviews.followUp(SessionUser.requireId(http), reviewId, request);
        return Result.success();
    }

    @DeleteMapping
    public Result<Void> delete(@PathVariable Long reviewId, HttpServletRequest http) {
        reviews.delete(SessionUser.requireId(http), reviewId);
        return Result.success();
    }
}
