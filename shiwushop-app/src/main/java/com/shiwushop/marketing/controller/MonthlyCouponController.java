package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.marketing.dto.ActivityReason;
import com.shiwushop.marketing.service.MonthlyCouponService;
import com.shiwushop.marketing.vo.MonthlyBenefitVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class MonthlyCouponController {
    private final MonthlyCouponService benefits;

    @GetMapping("/members/me/monthly-benefits")
    public Result<MonthlyBenefitVO> month(@RequestParam @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String month,
                                          HttpServletRequest request) {
        return Result.success(benefits.benefit(SessionUser.requireId(request), YearMonth.parse(month)));
    }

    @GetMapping("/admin/marketing/coupon-grants")
    public Result<MonthlyBenefitVO.Page> grants(@RequestParam(required = false) @Positive Long userId,
                                                @RequestParam(required = false) @Pattern(regexp = "PENDING|GRANTED|EXPIRED|ATTENTION") String status,
                                                @RequestParam(required = false) @Pattern(regexp = "MEMBERSHIP|CLAIM|CODE|COMPENSATION") String sourceKind,
                                                @RequestParam(required = false) @Positive Long afterId,
                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                HttpServletRequest request) {
        return Result.success(
                benefits.admin(SessionUser.requireId(request), userId, status, sourceKind, afterId, size));
    }

    @PostMapping("/admin/marketing/coupon-grants/{id}/retry")
    public ResponseEntity<Result<MonthlyBenefitVO.Retry>> retry(@PathVariable @Positive Long id,
                                                                @RequestBody @Valid ActivityReason body,
                                                                HttpServletRequest request) {
        var result = benefits.retry(SessionUser.requireId(request), id, body.reason());
        return ResponseEntity.status(Set.of("GRANTED", "EXPIRED").contains(result.state()) ? 200 : 202)
                .body(Result.success(result));
    }
}
