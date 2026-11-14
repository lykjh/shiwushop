package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.marketing.dto.StockTransfer;
import com.shiwushop.marketing.service.MarketingStockService;
import com.shiwushop.marketing.vo.StockPoolVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/merchant/shops/{shopId}/marketing")
public class MarketingStockTransferController {
    private final MarketingStockService stock;

    @PostMapping("/stock-pools/{activitySkuId}/transfers")
    public Result<StockPoolVO.Transfer> transfer(@PathVariable @Positive Long shopId,
                                                 @PathVariable @Positive Long activitySkuId,
                                                 @RequestHeader(value = "Idempotency-Key", defaultValue = "")
                                                 @Pattern(regexp = "[!-~]{1,64}") String key,
                                                 @Valid @RequestBody StockTransfer body,
                                                 HttpServletRequest request) {
        return Result.success(stock.transfer(SessionUser.requireId(request), shopId, activitySkuId, key, body));
    }
}
