package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.marketing.dto.BudgetAdjustment;
import com.shiwushop.marketing.service.MarketingBudgetService;
import com.shiwushop.marketing.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/admin/marketing", "/merchant/shops/{shopId}/marketing"})
public class MarketingBudgetController {
    private final MarketingBudgetService budgets;

    @GetMapping("/activities/{activityId}/budgets")
    public Result<List<BudgetVO>> list(@PathVariable(required = false) @Positive Long shopId,
                                       @PathVariable @Positive Long activityId, HttpServletRequest request) {
        return Result.success(budgets.list(SessionUser.requireId(request), shopId, activityId));
    }

    @GetMapping("/budgets/{budgetId}/entries")
    public Result<BudgetEntryPage> entries(@PathVariable(required = false) @Positive Long shopId,
                                           @PathVariable @Positive Long budgetId,
                                           @RequestParam(required = false) @Positive Long afterId,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                           HttpServletRequest request) {
        return Result.success(budgets.entries(SessionUser.requireId(request), shopId, budgetId, afterId, size));
    }

    @PostMapping("/budgets/{budgetId}/adjustments")
    public Result<BudgetAdjustmentResult> adjust(@PathVariable(required = false) @Positive Long shopId,
                                                 @PathVariable @Positive Long budgetId,
                                                 @RequestHeader(value = "Idempotency-Key", defaultValue = "") @Pattern(regexp = "[!-~]{1,64}") String key,
                                                 @Valid @RequestBody BudgetAdjustment body,
                                                 HttpServletRequest request) {
        return Result.success(budgets.adjust(SessionUser.requireId(request), shopId, budgetId, key, body));
    }
}
