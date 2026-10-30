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

    /**
     * 修改指定店铺的展示资料。请求体先经过字段校验；服务层根据当前用户 ID 检查店铺归属并保存。
     *
     * @param id 要修改的店铺 ID
     * @param dto 新的店铺资料
     * @param request 当前请求，用于取得操作人的用户 ID
     * @return 更新后的店铺资料
     */
    @PutMapping("/{id}")
    public Result<ShopVO> update(
            @PathVariable Long id,
            @Valid @RequestBody ShopProfileUpdateDTO dto,
            HttpServletRequest request
            ){
        return Result.success(shopService.updateProfile(id,SessionUser.currentId(request),dto));
    }

    /**
     * 列出当前用户管理的店铺。控制器提供会话用户 ID，查询与权限判断由店铺服务完成。
     *
     * @param request 当前请求
     * @return 当前用户可管理的店铺列表
     */
    @GetMapping
    public Result<List<ShopVO>> list(HttpServletRequest request) {
        return Result.success(shopService.listMine(SessionUser.currentId(request)));
    }

}
