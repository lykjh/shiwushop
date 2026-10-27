package com.shiwushop.controller.brand;

import com.shiwushop.vo.brand.BrandPublicVO;


import com.shiwushop.common.Result;
import com.shiwushop.service.BrandService;
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

    @GetMapping
    public Result<List<BrandPublicVO>> list(
            @RequestParam(required = false) Long categoryId
    ) {
        return Result.success(brandService.publicList(categoryId));
    }
}
