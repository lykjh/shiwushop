package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.marketing.service.MarketingStockService;
import com.shiwushop.marketing.vo.StockPoolVO;
import com.shiwushop.marketing.vo.StockEntryPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/admin/marketing", "/merchant/shops/{shopId}/marketing"})
public class MarketingStockController {
    private final MarketingStockService stock;

    @GetMapping("/activities/{activityId}/stock-pools")
    public Result<List<StockPoolVO>> pools(@PathVariable(required = false) @Positive Long shopId,
                                           @PathVariable @Positive Long activityId,
                                           HttpServletRequest request) {
        return Result.success(stock.pools(SessionUser.requireId(request), shopId, activityId));
    }

    @GetMapping("/stock-pools/{activitySkuId}/entries")
    public Result<StockEntryPage> entries(@PathVariable(required = false) @Positive Long shopId,
                                          @PathVariable @Positive Long activitySkuId,
                                          @RequestParam(required = false) @Positive Long afterId,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                          HttpServletRequest request) {
        return Result.success(stock.entries(SessionUser.requireId(request), shopId, activitySkuId, afterId, size));
    }

}
