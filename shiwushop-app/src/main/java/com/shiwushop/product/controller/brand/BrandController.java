package com.shiwushop.product.controller.brand;

import com.shiwushop.product.vo.brand.BrandPublicVO;


import com.shiwushop.common.Result;
import com.shiwushop.product.service.BrandService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/brands")
@RequiredArgsConstructor
public class BrandController {

    private final BrandService brandService;

    /** 列出公开品牌；传入分类 ID 时只返回该分类可用的品牌。 */
    @GetMapping
    public Result<List<BrandPublicVO>> list(
            @RequestParam(required = false) Long categoryId
    ) {
        return Result.success(brandService.publicList(categoryId));
    }
}
