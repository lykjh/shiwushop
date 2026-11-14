package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.marketing.service.MarketingCouponService;
import com.shiwushop.marketing.dto.CouponRedemption;
import jakarta.validation.Valid;
import com.shiwushop.marketing.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class UserCouponController {
    private final MarketingCouponService coupons;

    @PostMapping("/marketing/coupon-rules/{activityId}/claim")
    public Result<CouponGrantVO> claim(@PathVariable @Positive Long activityId,
                                       @RequestHeader(value = "Idempotency-Key", defaultValue = "") @Pattern(regexp = "[!-~]{1,64}") String key,
                                       HttpServletRequest request) {
        return Result.success(coupons.claim(SessionUser.requireId(request), activityId, key));
    }

    @PostMapping("/users/me/coupons/redeem")
    public Result<CouponGrantVO> redeem(@Valid @RequestBody CouponRedemption body,
                                        @RequestHeader(value = "Idempotency-Key", defaultValue = "") @Pattern(regexp = "[!-~]{1,64}") String key,
                                        HttpServletRequest request) {
        return Result.success(coupons.redeem(SessionUser.requireId(request), key, body));
    }

    @GetMapping("/users/me/coupons")
    public Result<CouponVO.Page> coupons(@RequestParam(required = false) @Positive Long afterId,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                         @RequestParam(required = false) @Pattern(regexp = "AVAILABLE|RESERVED|USED|EXPIRED") String status,
                                         @RequestParam(required = false) @Positive Long shopId,
                                         HttpServletRequest request) {
        return Result.success(coupons.coupons(SessionUser.requireId(request), afterId, size, status, shopId));
    }

    @GetMapping("/users/me/coupon-grants")
    public Result<CouponGrantVO.Page> grants(@RequestParam(required = false) @Positive Long afterId,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                             @RequestParam(required = false) @Pattern(regexp = "MEMBERSHIP|CLAIM|CODE|COMPENSATION") String sourceKind,
                                             HttpServletRequest request) {
        return Result.success(coupons.grants(SessionUser.requireId(request), afterId, size, sourceKind));
    }
}
