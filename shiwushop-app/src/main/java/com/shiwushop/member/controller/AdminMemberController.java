package com.shiwushop.member.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.member.dto.GrowthAdjustment;
import com.shiwushop.member.dto.PointAdjustment;
import com.shiwushop.member.vo.GrowthAdjustmentResult;
import com.shiwushop.member.vo.PointAdjustmentResult;
import com.shiwushop.member.service.MemberAdministrationService;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.member.vo.AdminGrowthEntryPage;
import com.shiwushop.member.vo.AdminPointEntryPage;
import com.shiwushop.member.vo.MemberOverview;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/members")
public class AdminMemberController {
    private final MemberAdministrationService administration;
    private final MemberAccountService accounts;

    @GetMapping("/{userId}")
    public Result<MemberOverview> overview(@PathVariable @Positive Long userId, HttpServletRequest request) {
        return Result.success(accounts.adminOverview(SessionUser.requireId(request), userId));
    }

    @GetMapping("/{userId}/growth-entries")
    public Result<AdminGrowthEntryPage> growthEntries(@PathVariable @Positive Long userId,
                                                      @RequestParam(required = false) @Positive Long afterId,
                                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                      @RequestParam(required = false) @Positive Long orderId,
                                                      HttpServletRequest request) {
        return Result.success(
                accounts.adminGrowthEntries(SessionUser.requireId(request), userId, afterId, size, orderId));
    }

    @GetMapping("/{userId}/point-entries")
    public Result<AdminPointEntryPage> pointEntries(@PathVariable @Positive Long userId,
                                                    @RequestParam(required = false) @Positive Long afterId,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                    @RequestParam(required = false) @Positive Long orderId,
                                                    HttpServletRequest request) {
        return Result.success(
                accounts.adminPointEntries(SessionUser.requireId(request), userId, afterId, size, orderId));
    }

    @PostMapping("/{userId}/growth-adjustments")
    public Result<GrowthAdjustmentResult> adjust(@PathVariable @Positive Long userId,
                                                 @RequestHeader(value = "Idempotency-Key", defaultValue = "") @Pattern(regexp = "[!-~]{1,64}") String key,
                                                 @Valid @RequestBody GrowthAdjustment body,
                                                 HttpServletRequest request) {
        return Result.success(administration.adjustGrowth(SessionUser.requireId(request), userId, key, body));
    }

    @PostMapping("/{userId}/point-adjustments")
    public Result<PointAdjustmentResult> adjustPoints(@PathVariable @Positive Long userId,
                                                      @RequestHeader(value = "Idempotency-Key", defaultValue = "") @Pattern(regexp = "[!-~]{1,64}") String key,
                                                      @Valid @RequestBody PointAdjustment body,
                                                      HttpServletRequest request) {
        return Result.success(administration.adjustPoints(SessionUser.requireId(request), userId, key, body));
    }
}
