package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.marketing.dto.ActivityDecision;
import com.shiwushop.marketing.dto.ActivityReason;
import com.shiwushop.marketing.service.MarketingActivityService;
import com.shiwushop.marketing.vo.ActivityAdminVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/marketing/activities/{id}")
public class MarketingActivityDecisionController {
    private final MarketingActivityService activities;

    @PostMapping("/decision")
    public ResponseEntity<Result<ActivityAdminVO>> decide(@PathVariable @Positive Long id,
                                                          @RequestHeader("If-Match") @NotBlank String etag,
                                                          @Valid @RequestBody ActivityDecision body,
                                                          HttpServletRequest request) {
        var result = activities.decide(SessionUser.requireId(request), id, etag, body);
        return ResponseEntity.ok().eTag(result.etag()).body(Result.success(result.activity()));
    }

    @PostMapping("/{command:pause|resume|terminate}")
    public ResponseEntity<Result<ActivityAdminVO>> transition(@PathVariable @Positive Long id,
                                                              @PathVariable String command,
                                                              @RequestHeader("If-Match") @NotBlank String etag,
                                                              @Valid @RequestBody ActivityReason body,
                                                              HttpServletRequest request) {
        var result = activities.transition(SessionUser.requireId(request), id, etag, command, body);
        return ResponseEntity.ok().eTag(result.etag()).body(Result.success(result.activity()));
    }
}
