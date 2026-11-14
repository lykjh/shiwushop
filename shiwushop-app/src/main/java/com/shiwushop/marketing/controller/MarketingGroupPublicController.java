package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.marketing.service.MarketingGroupPublicService;
import com.shiwushop.marketing.vo.GroupPublicVO;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/marketing")
public class MarketingGroupPublicController {
    private final MarketingGroupPublicService groups;

    @GetMapping("/activities/{activityId}/groups")
    public Result<GroupPublicVO.Page> available(@PathVariable @Positive long activityId,
                                                @RequestParam @Positive long activitySkuId,
                                                @RequestParam(required = false) @Positive Long afterId,
                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return Result.success(groups.available(activityId, activitySkuId, afterId, size));
    }

    @GetMapping("/groups/{groupId}")
    public Result<GroupPublicVO> detail(@PathVariable @Positive long groupId) {
        return Result.success(groups.detail(groupId));
    }
}
