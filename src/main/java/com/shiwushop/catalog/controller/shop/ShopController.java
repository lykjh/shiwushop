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

    /** 按 ID 查询店铺资料及其公开分类；服务层校验 ID 和店铺是否存在。 */
    @GetMapping("/{id}")
    public Result<ShopDetailVO> detail(@PathVariable Long id) {
        return Result.success(shopService.publicDetail(id));
    }
}
