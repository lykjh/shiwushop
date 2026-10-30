package com.shiwushop.catalog.controller.admin;

import com.shiwushop.catalog.dto.brand.BrandSaveDTO;
import com.shiwushop.catalog.dto.brand.BrandCreateDTO;
import com.shiwushop.catalog.vo.brand.BrandVO;
import com.shiwushop.catalog.vo.brand.AdminBrandListVO;


import com.shiwushop.common.Result;
import com.shiwushop.catalog.service.BrandService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/brands")
@RequiredArgsConstructor
public class AdminBrandController {

    private final BrandService brandService;
    private final AdminAuth adminAuth;

    /** 管理员创建品牌，并记录创建操作人。 */
    @PostMapping
    public Result<AdminBrandListVO> create(
            @Valid @RequestBody BrandCreateDTO dto, HttpServletRequest request){
        Long adminId = adminAuth.requireAdmin(request);
        return Result.success(brandService.create(dto, adminId));
    }

    /** 管理员查看品牌管理列表，包括品牌的管理状态。 */
    @GetMapping
    public Result<List<AdminBrandListVO>> list(HttpServletRequest request) {
        adminAuth.requireAdmin(request);
        return Result.success(brandService.adminList());
    }

    /** 管理员修改指定品牌的资料，服务层处理归属和字段校验。 */
    @PutMapping("/{id}")
    public Result<BrandVO> update(
            @PathVariable Long id,
            @Valid @RequestBody BrandSaveDTO dto,
            HttpServletRequest request) {
        Long adminId = adminAuth.requireAdmin(request);
        return Result.success(brandService.update(id, dto, adminId));
    }


}
