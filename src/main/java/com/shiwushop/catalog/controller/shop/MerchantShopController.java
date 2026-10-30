package com.shiwushop.catalog.controller.shop;

import com.shiwushop.catalog.dto.shop.ShopProfileUpdateDTO;
import com.shiwushop.catalog.vo.shop.ShopVO;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.catalog.service.ShopService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 这里只处理店铺资料；商家订单由订单控制器处理，避免两类接口共用依赖。 */
@RestController
@RequestMapping("/merchant/shops")
@RequiredArgsConstructor
public class MerchantShopController {

    private final ShopService shopService;
    @PutMapping("/{id}")
    public Result<ShopVO> update(
            @PathVariable Long id,
            @Valid @RequestBody ShopProfileUpdateDTO dto,
            HttpServletRequest request
            ){
        return Result.success(shopService.updateProfile(id,SessionUser.currentId(request),dto));
    }

    @GetMapping
    public Result<List<ShopVO>> list(HttpServletRequest request) {
        return Result.success(shopService.listMine(SessionUser.currentId(request)));
    }

}
