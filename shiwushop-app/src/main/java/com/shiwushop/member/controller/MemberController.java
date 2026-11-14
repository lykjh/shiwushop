package com.shiwushop.member.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.member.vo.MemberGrowthEntryVO;
import com.shiwushop.member.vo.MemberPointEntryVO;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.member.vo.MemberOverview;
import com.shiwushop.member.vo.MemberLevelVO;
import com.shiwushop.member.vo.MemberPointLotPage;
import com.shiwushop.member.vo.MemberRewardTaskPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/members")
@RequiredArgsConstructor
public class MemberController {
    private final MemberAccountService accounts;

    @GetMapping("/levels")
    public Result<List<MemberLevelVO>> levels() {
        return Result.success(accounts.levels());
    }

    @GetMapping("/me")
    public Result<MemberOverview> overview(HttpServletRequest request) {
        return Result.success(accounts.overview(SessionUser.requireId(request)));
    }

    @GetMapping("/me/growth-entries")
    public Result<List<MemberGrowthEntryVO>> growth(
            @RequestParam(required = false) @Positive Long beforeId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit, HttpServletRequest request) {
        return Result.success(accounts.growthEntries(SessionUser.requireId(request), beforeId, limit));
    }

    @GetMapping("/me/point-entries")
    public Result<List<MemberPointEntryVO>> points(
            @RequestParam(required = false) @Positive Long beforeId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit, HttpServletRequest request) {
        return Result.success(accounts.pointEntries(SessionUser.requireId(request), beforeId, limit));
    }

    @GetMapping("/me/point-lots")
    public Result<MemberPointLotPage> lots(
            @RequestParam(required = false) @Positive Long afterId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|EXHAUSTED|EXPIRED") String status,
            HttpServletRequest request) {
        return Result.success(accounts.pointLots(SessionUser.requireId(request), afterId, size, status));
    }

    @GetMapping("/me/reward-tasks")
    public Result<MemberRewardTaskPage> tasks(
            @RequestParam(required = false) @Positive Long afterId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Pattern(regexp = "GROWTH|POINTS") String rewardKind,
            @RequestParam(required = false) @Pattern(regexp = "WAIT_PAYMENT|WAIT_RECEIPT|WAIT_AFTERSALE|READY|APPLIED|EXCLUDED|ATTENTION") String status,
            @RequestParam(required = false) @Positive Long orderId, HttpServletRequest request) {
        return Result.success(
                accounts.rewardTasks(SessionUser.requireId(request), afterId, size, rewardKind, status, orderId));
    }
}
