package com.shiwushop.member.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.member.dto.RetryRewardTask;
import com.shiwushop.member.service.MemberTaskService;
import com.shiwushop.member.vo.AdminRewardTaskPage;
import com.shiwushop.member.vo.TaskRetryResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/marketing/reward-tasks")
public class AdminRewardTaskController {
    private final MemberTaskService tasks;

    @GetMapping
    public Result<AdminRewardTaskPage> list(
            @RequestParam(required = false) @Positive Long afterId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Pattern(regexp = "GROWTH|POINTS") String rewardKind,
            @RequestParam(required = false) @Pattern(regexp = "WAIT_PAYMENT|WAIT_RECEIPT|WAIT_AFTERSALE|READY|APPLIED|EXCLUDED|ATTENTION") String status,
            @RequestParam(required = false) @Positive Long userId,
            @RequestParam(required = false) @Positive Long orderId, HttpServletRequest request) {
        return Result.success(
                tasks.adminTasks(SessionUser.requireId(request), userId, afterId, size, rewardKind, status, orderId));
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<Result<TaskRetryResult>> retry(
            @PathVariable @Positive Long taskId, @Valid @RequestBody RetryRewardTask body, HttpServletRequest request) {
        var result = tasks.retry(SessionUser.requireId(request), taskId, body.reason());
        return ResponseEntity.status("APPLIED".equals(result.state()) ? 200 : 202).body(Result.success(result));
    }
}
