package com.shiwushop.controller.admin;

import com.shiwushop.dto.brand.BrandSaveDTO;
import com.shiwushop.dto.brand.BrandCreateDTO;
import com.shiwushop.vo.brand.BrandVO;
import com.shiwushop.vo.brand.AdminBrandListVO;


import com.shiwushop.common.Result;
import com.shiwushop.service.BrandService;
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

    @PostMapping
    public Result<AdminBrandListVO> create(
            @Valid @RequestBody BrandCreateDTO dto, HttpServletRequest request){
        Long adminId = adminAuth.requireAdmin(request);
        return Result.success(brandService.create(dto, adminId));
    }

    @GetMapping
    public Result<List<AdminBrandListVO>> list(HttpServletRequest request) {
        adminAuth.requireAdmin(request);
        return Result.success(brandService.adminList());
    }

    @PutMapping("/{id}")
    public Result<BrandVO> update(
            @PathVariable Long id,
            @Valid @RequestBody BrandSaveDTO dto,
            HttpServletRequest request) {
        Long adminId = adminAuth.requireAdmin(request);
        return Result.success(brandService.update(id, dto, adminId));
    }


}
