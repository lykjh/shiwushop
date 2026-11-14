package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.marketing.service.MarketingActivityPublicService;
import com.shiwushop.marketing.vo.ActivityPublicVO;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/marketing/activities")
public class MarketingActivityPublicController {
    private final MarketingActivityPublicService activities;

    @GetMapping
    public Result<ActivityPublicVO.Page> list(@RequestParam(required = false) @Positive Long afterId,
                                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                              @RequestParam(required = false) @Pattern(regexp = "MEMBER_PRICE|COUPON|LIMITED_PRICE|FLASH|GROUP|BARGAIN|FULL_REDUCTION|FULL_GIFT|POINTS") String type,
                                              @RequestParam(required = false) @Positive Long shopId,
                                              @RequestParam(required = false) @Positive Long skuId) {
        return Result.success(activities.list(afterId, size, type, shopId, skuId));
    }

    @GetMapping("/{id}")
    public Result<ActivityPublicVO> detail(@PathVariable @Positive Long id) {
        return Result.success(activities.detail(id));
    }
}
