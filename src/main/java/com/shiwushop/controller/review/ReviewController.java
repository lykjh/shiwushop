package com.shiwushop.controller.review;

import com.shiwushop.dto.review.ReviewCreateDTO;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.service.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders/{orderId}/items/{orderItemId}/review")
@RequiredArgsConstructor
public class ReviewController {
    private final ReviewService reviews;

    @PostMapping
    public Result<Created> create(@PathVariable Long orderId, @PathVariable Long orderItemId,
                                  @RequestBody ReviewCreateDTO request, HttpServletRequest http) {
        return Result.success(new Created(reviews.create(SessionUser.requireId(http), orderId, orderItemId, request)));
    }

    public record Created(Long id) { }
}
