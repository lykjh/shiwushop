package com.shiwushop.catalog.controller.shop;

import com.shiwushop.catalog.vo.shop.ShopDetailVO;
import com.shiwushop.catalog.vo.shop.ShopPageVO;

import com.shiwushop.common.Result;
import com.shiwushop.catalog.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shops")
@RequiredArgsConstructor
public class ShopController {
    private final ShopService shopService;

    /** 按可选分类筛选公开店铺，并使用页码和每页条数返回一页结果。 */
    @GetMapping
    public Result<ShopPageVO> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(shopService.publicList(categoryId, page, size));
    }

    /** 查询一家公开店铺的展示资料；服务层检查店铺是否对外可见。 */
    @GetMapping("/{id}")
    public Result<ShopDetailVO> detail(@PathVariable Long id) {
        return Result.success(shopService.publicDetail(id));
    }
}
