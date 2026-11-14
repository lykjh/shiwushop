package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.marketing.service.MarketingBargainQueryService;
import com.shiwushop.marketing.vo.BargainPublicVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/users/me/marketing/bargains")
public class MarketingBargainMineController {
    private final MarketingBargainQueryService bargains;

    @GetMapping
    public Result<BargainPublicVO.Page> mine(@RequestParam(required = false) @Positive Long afterId,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                             @RequestParam(required = false)
                                             @Pattern(regexp = "ACTIVE|QUALIFIED|ORDERED|EXPIRED|CLOSED") String status,
                                             HttpServletRequest request) {
        return Result.success(bargains.mine(SessionUser.requireId(request), afterId, size, status));
    }
}
