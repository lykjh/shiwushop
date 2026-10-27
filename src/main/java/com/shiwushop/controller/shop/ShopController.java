package com.shiwushop.controller.shop;

import com.shiwushop.vo.shop.ShopDetailVO;
import com.shiwushop.vo.shop.ShopPageVO;

import com.shiwushop.common.Result;
import com.shiwushop.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shops")
@RequiredArgsConstructor
public class ShopController {
    private final ShopService shopService;

    @GetMapping
    public Result<ShopPageVO> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(shopService.publicList(categoryId, page, size));
    }

    @GetMapping("/{id}")
    public Result<ShopDetailVO> detail(@PathVariable Long id) {
        return Result.success(shopService.publicDetail(id));
    }
}