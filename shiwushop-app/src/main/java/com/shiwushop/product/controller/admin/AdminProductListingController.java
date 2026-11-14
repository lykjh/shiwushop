package com.shiwushop.product.controller.admin;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.AdminAuth;
import com.shiwushop.product.dto.product.ProductListingDecisionDTO;
import com.shiwushop.product.service.ProductService;
import com.shiwushop.product.vo.product.ProductListingVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/products")
@RequiredArgsConstructor
public class AdminProductListingController {
    private final ProductService products;
    private final AdminAuth adminAuth;

    @PostMapping("/{id}/decision")
    public Result<ProductListingVO> decide(@PathVariable @Positive Long id,
                                           @Valid @RequestBody ProductListingDecisionDTO body,
                                           HttpServletRequest request) {
        return Result.success(products.decideListing(adminAuth.requireAdmin(request), id, body));
    }
}
