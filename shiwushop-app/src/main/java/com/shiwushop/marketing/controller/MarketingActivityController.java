package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.marketing.dto.*;
import com.shiwushop.marketing.service.MarketingActivityService;
import com.shiwushop.marketing.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/admin/marketing", "/merchant/shops/{shopId}/marketing"})
public class MarketingActivityController {
    private final MarketingActivityService activities;

    private ResponseEntity<Result<ActivityAdminVO>> response(ActivityAdminVO.Version result) {
        return ResponseEntity.ok().eTag(result.etag()).body(Result.success(result.activity()));
    }

    @GetMapping("/activities")
    public Result<ActivityAdminVO.Page> list(@PathVariable(required = false) @Positive Long shopId,
                                             @RequestParam(required = false) @Positive Long afterId,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                             @RequestParam(required = false) @Pattern(regexp = "MEMBER_PRICE|COUPON|LIMITED_PRICE|FLASH|GROUP|BARGAIN|FULL_REDUCTION|FULL_GIFT|POINTS") String type,
                                             @RequestParam(required = false) @Pattern(regexp = "DRAFT|PENDING_REVIEW|APPROVED|RUNNING|ENDED|REJECTED|PAUSED|TERMINATED") String status,
                                             @RequestParam(required = false) @Size(min = 1, max = 64) String activityCode,
                                             HttpServletRequest request) {
        return Result.success(
                activities.list(SessionUser.requireId(request), shopId, afterId, size, type, status, activityCode));
    }

    @GetMapping("/activities/{id}")
    public ResponseEntity<Result<ActivityAdminVO>> detail(@PathVariable(required = false) @Positive Long shopId,
                                                          @PathVariable @Positive Long id, HttpServletRequest request) {
        return response(activities.detail(SessionUser.requireId(request), shopId, id));
    }

    @PostMapping("/activities")
    public ResponseEntity<Result<ActivityAdminVO>> create(@PathVariable(required = false) @Positive Long shopId,
                                                          @RequestHeader(value = "Idempotency-Key", defaultValue = "") @Pattern(regexp = "[!-~]{1,64}") String key,
                                                          @Valid @RequestBody ActivityDraft body,
                                                          HttpServletRequest request) {
        return response(activities.create(SessionUser.requireId(request), shopId, key, body));
    }

    @PutMapping("/activities/{id}")
    public ResponseEntity<Result<ActivityAdminVO>> edit(@PathVariable(required = false) @Positive Long shopId,
                                                        @PathVariable @Positive Long id,
                                                        @RequestHeader("If-Match") @NotBlank String etag,
                                                        @Valid @RequestBody ActivityDraft body,
                                                        HttpServletRequest request) {
        return response(activities.edit(SessionUser.requireId(request), shopId, id, etag, body));
    }

    @PostMapping("/activities/{id}/submit")
    public ResponseEntity<Result<ActivityAdminVO>> submit(@PathVariable(required = false) @Positive Long shopId,
                                                          @PathVariable @Positive Long id,
                                                          @RequestHeader("If-Match") @NotBlank String etag,
                                                          HttpServletRequest request) {
        return response(activities.submit(SessionUser.requireId(request), shopId, id, etag));
    }

    @PostMapping("/activities/{id}/versions")
    public ResponseEntity<Result<ActivityAdminVO>> revise(@PathVariable(required = false) @Positive Long shopId,
                                                          @PathVariable @Positive Long id,
                                                          @RequestHeader(value = "Idempotency-Key", defaultValue = "") @Pattern(regexp = "[!-~]{1,64}") String key,
                                                          @Valid @RequestBody ActivityReason body,
                                                          HttpServletRequest request) {
        return response(activities.revise(SessionUser.requireId(request), shopId, id, key, body));
    }
}
