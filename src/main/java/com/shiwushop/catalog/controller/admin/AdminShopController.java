package com.shiwushop.catalog.controller.admin;

import com.shiwushop.catalog.dto.shop.ShopCreateDTO;
import com.shiwushop.catalog.dto.shop.ShopOwnerUpdateDTO;
import com.shiwushop.catalog.dto.shop.ShopStatusUpdateDTO;
import com.shiwushop.catalog.vo.shop.ShopVO;

import com.shiwushop.common.Result;
import com.shiwushop.catalog.service.ShopService;
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

    /** 管理员创建店铺，并将管理员 ID 传给服务层记录操作人。 */
    @PostMapping
    public Result<ShopVO> create(@Valid @RequestBody ShopCreateDTO dto, HttpServletRequest request) {
        Long adminId = adminAuth.requireAdmin(request);
        return Result.success(shopService.create(dto, adminId));
    }

    /** 将指定店铺转交给另一位用户；服务层处理店主资格和并发归属变更。 */
    @PutMapping("/{id}/owner")
    public Result<ShopVO> changeOwner(@PathVariable Long id, @RequestBody @Valid ShopOwnerUpdateDTO dto, HttpServletRequest request) {
        adminAuth.requireAdmin(request);
        return Result.success(shopService.changeOwner(id, dto.getOwnerUserId()));
    }

    /** 启用或停用指定店铺；服务层更新店铺对外可见状态。 */
    @PutMapping("/{id}/status")
    public Result<ShopVO> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody ShopStatusUpdateDTO dto,
            HttpServletRequest request
    ) {
        adminAuth.requireAdmin(request);
        return Result.success(shopService.changeStatus(id, dto.getEnabled()));
    }

    /** 管理员查看全部店铺，包括普通用户列表不会显示的状态。 */
    @GetMapping
    public Result<List<ShopVO>> list(HttpServletRequest request) {
        adminAuth.requireAdmin(request);
        return Result.success(shopService.adminList());
    }
}
