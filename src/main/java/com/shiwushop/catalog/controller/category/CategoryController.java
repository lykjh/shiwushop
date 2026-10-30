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

    /** 按 ID 查询公开分类的详情；分类不存在或不可见时由服务层处理。 */
    @GetMapping("/{id}")
    public Result<CategoryDetailVO> detail(@PathVariable Long id){
        return Result.success(categoryService.detail(id));
    }

    /** 查询顶层分类或指定父分类下的可见子分类，用于商品导航。 */
    @GetMapping
    public Result<List<CategoryNavVO>> list(@RequestParam(required = false) Long parentId) {
        return Result.success(categoryService.list(parentId));
    }
}
