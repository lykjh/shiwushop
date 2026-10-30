package com.shiwushop.catalog.controller.admin;

import com.shiwushop.catalog.dto.category.CategoryCreateDTO;
import com.shiwushop.catalog.dto.category.CategoryUpdateDTO;
import com.shiwushop.catalog.vo.category.CategoryVO;

import com.shiwushop.common.Result;
import com.shiwushop.catalog.service.CategoryService;
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

    /** 管理员创建商品分类；分类层级与名称约束由分类服务校验。 */
    @PostMapping
    public Result<CategoryVO> create(
            @Valid @RequestBody CategoryCreateDTO dto,
            HttpServletRequest request
    ) {
        adminAuth.requireAdmin(request);
        return Result.success(categoryService.create(dto));
    }

    /** 管理员查看完整分类列表，包括公开导航中隐藏的分类。 */
    @GetMapping
    public Result<List<CategoryVO>> list(HttpServletRequest request) {
        adminAuth.requireAdmin(request);
        return Result.success(categoryService.adminList());
    }

    /** 管理员修改分类资料或父子关系；服务层检查层级约束。 */
    @PutMapping("/{id}")
    public Result<CategoryVO> update(
            @PathVariable Long id,
            @Valid @RequestBody CategoryUpdateDTO dto,
            HttpServletRequest request
    ) {
        adminAuth.requireAdmin(request);
        return Result.success(categoryService.update(id, dto));
    }

    /** 管理员删除指定分类；服务层检查关联和删除条件。 */
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
