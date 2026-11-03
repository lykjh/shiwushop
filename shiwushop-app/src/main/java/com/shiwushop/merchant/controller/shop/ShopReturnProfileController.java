package com.shiwushop.merchant.controller.shop;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.merchant.dto.shop.ShopReturnProfileSaveDTO;
import com.shiwushop.merchant.service.ShopReturnProfileService;
import com.shiwushop.merchant.vo.shop.ShopReturnProfileVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/merchant/shops/{shopId}/return-profile")
@RequiredArgsConstructor
public class ShopReturnProfileController {
    private final ShopReturnProfileService service;

    /** 读取当前店主的私密退货资料。 */
    @GetMapping
    public Result<ShopReturnProfileVO> get(@PathVariable @Positive Long shopId, HttpServletRequest request) {
        return Result.success(service.get(shopId, SessionUser.requireId(request)));
    }

    /** 校验并整体保存当前店主的私密退货资料。 */
    @PutMapping
    public Result<ShopReturnProfileVO> save(@PathVariable @Positive Long shopId, @Valid @RequestBody ShopReturnProfileSaveDTO dto, HttpServletRequest request) {
        return Result.success(service.save(shopId, SessionUser.requireId(request), dto));
    }
}
