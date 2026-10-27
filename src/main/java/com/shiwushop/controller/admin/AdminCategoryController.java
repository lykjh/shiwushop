package com.shiwushop.controller.admin;

import com.shiwushop.dto.category.CategoryCreateDTO;
import com.shiwushop.dto.category.CategoryUpdateDTO;
import com.shiwushop.vo.category.CategoryVO;

import com.shiwushop.common.Result;
import com.shiwushop.service.CategoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService categoryService;
    private final AdminAuth adminAuth;

    @PostMapping
    public Result<CategoryVO> create(
            @Valid @RequestBody CategoryCreateDTO dto,
            HttpServletRequest request
    ) {
        adminAuth.requireAdmin(request);
        return Result.success(categoryService.create(dto));
    }

    @GetMapping
    public Result<List<CategoryVO>> list(HttpServletRequest request) {
        adminAuth.requireAdmin(request);
        return Result.success(categoryService.adminList());
    }

    @PutMapping("/{id}")
    public Result<CategoryVO> update(
            @PathVariable Long id,
            @Valid @RequestBody CategoryUpdateDTO dto,
            HttpServletRequest request
    ) {
        adminAuth.requireAdmin(request);
        return Result.success(categoryService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @PathVariable Long id,
            HttpServletRequest request
    ) {
        adminAuth.requireAdmin(request);
        categoryService.delete(id);
        return Result.success();
    }
}
