package com.shiwushop.controller.admin;

import com.shiwushop.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.vo.product.ProductStatusVO;

import com.shiwushop.common.Result;
import com.shiwushop.service.ProductService;
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
    private final ProductService productService;
    private final AdminAuth adminAuth;

    @PutMapping("/{id}/status")
    public Result<ProductStatusVO> takeDown(
            @PathVariable Long id,
            @Valid @RequestBody ProductStatusUpdateDTO dto,
            HttpServletRequest request) {
        adminAuth.requireAdmin(request);
        return Result.success(productService.adminTakeDown(id, dto));
    }
}
