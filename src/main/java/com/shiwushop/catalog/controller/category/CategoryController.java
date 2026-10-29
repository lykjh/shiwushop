package com.shiwushop.catalog.controller.category;

import com.shiwushop.catalog.vo.category.CategoryDetailVO;
import com.shiwushop.catalog.vo.category.CategoryNavVO;

import com.shiwushop.common.Result;
import com.shiwushop.catalog.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping("/{id}")
    public Result<CategoryDetailVO> detail(@PathVariable Long id){
        return Result.success(categoryService.detail(id));
    }

    @GetMapping
    public Result<List<CategoryNavVO>> list(@RequestParam(required = false) Long parentId) {
        return Result.success(categoryService.list(parentId));
    }
}
