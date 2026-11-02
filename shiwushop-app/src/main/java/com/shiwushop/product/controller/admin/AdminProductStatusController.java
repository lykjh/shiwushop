package com.shiwushop.product.controller.admin;

import com.shiwushop.common.auth.AdminAuth;
import com.shiwushop.product.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.product.vo.product.ProductStatusVO;

import com.shiwushop.common.Result;
import com.shiwushop.product.service.AdminProductStatusService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/products")
@RequiredArgsConstructor
public class AdminProductStatusController {
    private final AdminProductStatusService productService;
    private final AdminAuth adminAuth;

    /** 管理员对指定商品执行下架；具体状态转换交给商品服务。 */
    @PutMapping("/{id}/status")
    public Result<ProductStatusVO> takeDown(
            @PathVariable Long id,
            @Valid @RequestBody ProductStatusUpdateDTO dto,
            HttpServletRequest request) {
        adminAuth.requireAdmin(request);
        return Result.success(productService.adminTakeDown(id, dto));
    }
}
