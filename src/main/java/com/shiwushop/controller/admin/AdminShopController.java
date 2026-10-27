package com.shiwushop.controller.admin;

import com.shiwushop.dto.shop.ShopCreateDTO;
import com.shiwushop.dto.shop.ShopOwnerUpdateDTO;
import com.shiwushop.dto.shop.ShopStatusUpdateDTO;
import com.shiwushop.vo.shop.ShopVO;

import com.shiwushop.common.Result;
import com.shiwushop.service.ShopService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/shops")
@RequiredArgsConstructor
public class AdminShopController {

    private final ShopService shopService;
    private final AdminAuth adminAuth;

    @PostMapping
    public Result<ShopVO> create(@Valid @RequestBody ShopCreateDTO dto, HttpServletRequest request) {
        Long adminId = adminAuth.requireAdmin(request);
        return Result.success(shopService.create(dto, adminId));
    }

    @PutMapping("/{id}/owner")
    public Result<ShopVO> changeOwner(@PathVariable Long id, @RequestBody @Valid ShopOwnerUpdateDTO dto, HttpServletRequest request) {
        adminAuth.requireAdmin(request);
        return Result.success(shopService.changeOwner(id, dto.getOwnerUserId()));
    }

    @PutMapping("/{id}/status")
    public Result<ShopVO> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody ShopStatusUpdateDTO dto,
            HttpServletRequest request
    ) {
        adminAuth.requireAdmin(request);
        return Result.success(shopService.changeStatus(id, dto.getEnabled()));
    }

    @GetMapping
    public Result<List<ShopVO>> list(HttpServletRequest request) {
        adminAuth.requireAdmin(request);
        return Result.success(shopService.adminList());
    }
}
