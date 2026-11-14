package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.marketing.dto.*;
import com.shiwushop.marketing.service.MarketingCouponService;
import com.shiwushop.marketing.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/marketing")
public class MarketingCouponAdministrationController {
    private final MarketingCouponService coupons;

    @PostMapping("/coupon-rules/{id}/grants")
    public Result<CouponGrantVO> compensate(@PathVariable @Positive Long id,
                                            @RequestHeader(value = "Idempotency-Key", defaultValue = "") @Pattern(regexp = "[!-~]{1,64}") String key,
                                            @Valid @RequestBody CompensationGrant body, HttpServletRequest request) {
        return Result.success(coupons.compensate(SessionUser.requireId(request), id, key, body));
    }

    @PostMapping("/coupon-rules/{id}/codes")
    public Result<List<CouponCodeVO>> createCodes(@PathVariable @Positive Long id,
                                                  @RequestHeader(value = "Idempotency-Key", defaultValue = "") @Pattern(regexp = "[!-~]{1,64}") String key,
                                                  @Valid @RequestBody CouponCodeBatch body,
                                                  HttpServletRequest request) {
        return Result.success(coupons.createCodes(SessionUser.requireId(request), id, key, body));
    }

    @GetMapping("/coupon-rules/{id}/codes")
    public Result<CouponCodeVO.Page> codes(@PathVariable @Positive Long id,
                                           @RequestParam(required = false) @Positive Long afterId,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                           @RequestParam(required = false) @Pattern(regexp = "ACTIVE|DISABLED") String status,
                                           HttpServletRequest request) {
        return Result.success(coupons.codes(SessionUser.requireId(request), id, afterId, size, status));
    }

    @PostMapping("/coupon-codes/{id}/disable")
    public Result<CouponCodeVO> disable(@PathVariable @Positive Long id, @Valid @RequestBody ActivityReason body,
                                        HttpServletRequest request) {
        return Result.success(coupons.disableCode(SessionUser.requireId(request), id, body));
    }
}
