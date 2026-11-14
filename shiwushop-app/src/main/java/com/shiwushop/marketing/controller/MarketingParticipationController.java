package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.marketing.service.MarketingParticipationQueryService;
import com.shiwushop.marketing.vo.ParticipationPage;
import com.shiwushop.marketing.vo.PointRedemptionPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/users/me")
public class MarketingParticipationController {
    private final MarketingParticipationQueryService queries;

    @GetMapping("/marketing/participations")
    public Result<ParticipationPage> participations(@RequestParam(required = false) @Positive Long afterId,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                    @RequestParam(required = false)
                                                    @Pattern(regexp = "FLASH|GROUP|BARGAIN|POINTS") String type,
                                                    @RequestParam(required = false)
                                                    @Pattern(regexp = "RESERVED|PAID|QUALIFIED|RELEASED|CLOSED|REFUND_PENDING|REFUNDED") String status,
                                                    HttpServletRequest request) {
        return Result.success(queries.participations(SessionUser.requireId(request), afterId, size, type, status));
    }

    @GetMapping("/point-redemptions")
    public Result<PointRedemptionPage> redemptions(@RequestParam(required = false) @Positive Long afterId,
                                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                  @RequestParam(required = false)
                                                  @Pattern(regexp = "CREATED|FULFILLING|COMPLETED|CANCELLED|RETURNING|RETURNED") String status,
                                                  HttpServletRequest request) {
        return Result.success(queries.redemptions(SessionUser.requireId(request), afterId, size, status));
    }
}
