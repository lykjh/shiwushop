package com.shiwushop.marketing.controller;

import com.shiwushop.common.Result;
import com.shiwushop.marketing.service.MarketingPointProductService;
import com.shiwushop.marketing.vo.PointProductPage;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/marketing/point-products")
public class MarketingPointProductController {
    private final MarketingPointProductService products;

    @GetMapping
    public Result<PointProductPage> available(@RequestParam(required = false) @Positive Long shopId,
                                              @RequestParam(required = false) @Positive Long afterId,
                                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return Result.success(products.available(shopId, afterId, size));
    }
}
