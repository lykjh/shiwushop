package com.shiwushop.merchant.controller.shop;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.merchant.dto.staff.ShopStaffGrantDTO;
import com.shiwushop.merchant.service.ShopStaffService;
import com.shiwushop.merchant.vo.staff.ShopStaffVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/merchant/shops/{shopId}/staff")
@RequiredArgsConstructor
public class ShopStaffController {
    private final ShopStaffService staff;

    @PutMapping("/{userId}")
    public Result<ShopStaffVO> grant(@PathVariable Long shopId, @PathVariable Long userId,
                                    @Valid @RequestBody ShopStaffGrantDTO body, HttpServletRequest request) {
        return Result.success(staff.grant(SessionUser.requireId(request), shopId, userId, body.roles()));
    }

    @DeleteMapping("/{userId}")
    public Result<ShopStaffVO> revoke(@PathVariable Long shopId, @PathVariable Long userId,
                                     HttpServletRequest request) {
        return Result.success(staff.revoke(SessionUser.requireId(request), shopId, userId));
    }

    @GetMapping
    public Result<List<ShopStaffVO>> list(@PathVariable Long shopId, HttpServletRequest request) {
        return Result.success(staff.list(SessionUser.requireId(request), shopId));
    }
}
