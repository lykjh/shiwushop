package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.marketing.service.MarketingBargainHelpService;
import com.shiwushop.marketing.service.MarketingBargainQueryService;
import com.shiwushop.marketing.service.MarketingBargainStartService;
import com.shiwushop.marketing.dto.BargainStart;
import com.shiwushop.marketing.vo.BargainPublicVO;
import com.shiwushop.marketing.vo.BargainHelpVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/marketing/bargains")
public class MarketingBargainHelpController {
    private final MarketingBargainHelpService bargains;
    private final MarketingBargainQueryService queries;
    private final MarketingBargainStartService starts;

    @PostMapping
    public Result<BargainPublicVO.Mine> start(@RequestHeader(value = "Idempotency-Key", defaultValue = "")
                                              @Pattern(regexp = "[!-~]{1,64}") String key,
                                              @Valid @RequestBody BargainStart body,
                                              HttpServletRequest request) {
        return Result.success(starts.start(SessionUser.requireId(request), body.activitySkuId(), key));
    }

    @GetMapping("/{bargainId}")
    public Result<BargainPublicVO> detail(@PathVariable @Positive long bargainId) {
        return Result.success(queries.detail(bargainId));
    }

    @PostMapping("/{bargainId}/helps")
    public Result<BargainHelpVO> help(@PathVariable @Positive long bargainId,
                                     @RequestHeader(value = "Idempotency-Key", defaultValue = "")
                                     @Pattern(regexp = "[!-~]{1,64}") String key,
                                     HttpServletRequest request) {
        return Result.success(bargains.help(SessionUser.requireId(request), bargainId, key));
    }
}
