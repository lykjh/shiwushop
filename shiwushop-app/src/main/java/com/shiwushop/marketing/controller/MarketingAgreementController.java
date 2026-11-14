package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.marketing.dto.ActivityAgreement;
import com.shiwushop.marketing.service.MarketingActivityService;
import com.shiwushop.marketing.vo.BudgetVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/merchant/shops/{shopId}/marketing/activities/{id}/agreement")
public class MarketingAgreementController {
    private final MarketingActivityService activities;

    @PutMapping
    public Result<BudgetVO> agree(@PathVariable @Positive Long shopId, @PathVariable @Positive Long id,
                                  @RequestHeader("If-Match") @NotBlank String etag,
                                  @Valid @RequestBody ActivityAgreement body, HttpServletRequest request) {
        return Result.success(activities.agree(SessionUser.requireId(request), shopId, id, etag, body));
    }
}
