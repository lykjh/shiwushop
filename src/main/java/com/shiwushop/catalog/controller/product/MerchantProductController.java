package com.shiwushop.catalog.controller.product;

import com.shiwushop.catalog.dto.product.MerchantProductCreateDTO;
import com.shiwushop.catalog.dto.product.MerchantProductUpdateDTO;
import com.shiwushop.catalog.dto.product.ProductOptionsUpdateDTO;
import com.shiwushop.catalog.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.catalog.dto.sku.MerchantSkuCreateDTO;
import com.shiwushop.catalog.dto.sku.MerchantSkuUpdateDTO;
import com.shiwushop.catalog.vo.product.MerchantProductDetailVO;
import com.shiwushop.catalog.vo.product.MerchantProductPageVO;
import com.shiwushop.catalog.vo.product.MerchantProductVO;
import com.shiwushop.catalog.vo.product.ProductOptionVO;
import com.shiwushop.catalog.vo.product.ProductStatusVO;
import com.shiwushop.catalog.vo.sku.MerchantSkuVO;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.catalog.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/merchant/products")
@RequiredArgsConstructor
public class MerchantProductController {
    private final ProductService productService;

    @PostMapping
    public Result<MerchantProductVO> create(
            @Valid @RequestBody MerchantProductCreateDTO dto,
            HttpServletRequest request) {
        return Result.success(productService.createDraft(SessionUser.currentId(request), dto));
    }

    @PutMapping("/{id}")
    public Result<MerchantProductVO> update(
            @PathVariable Long id,
            @Valid @RequestBody MerchantProductUpdateDTO dto,
            HttpServletRequest request) {
        return Result.success(
                productService.updateMerchantProduct(SessionUser.currentId(request), id, dto));
    }

    @PutMapping("/{id}/options")
    public Result<List<ProductOptionVO>> replaceOptions(
            @PathVariable Long id,
            @Valid @RequestBody ProductOptionsUpdateDTO dto,
            HttpServletRequest request) {
        return Result.success(
                productService.replaceOptions(SessionUser.currentId(request), id, dto));
    }

    @PostMapping("/{id}/skus")
    public Result<MerchantSkuVO> createSku(
            @PathVariable Long id,
            @Valid @RequestBody MerchantSkuCreateDTO dto,
            HttpServletRequest request) {
        return Result.success(
                productService.createMerchantSku(SessionUser.currentId(request), id, dto));
    }

    @PutMapping("/{productId}/skus/{skuId}")
    public Result<MerchantSkuVO> updateSku(
            @PathVariable Long productId,
            @PathVariable Long skuId,
            @Valid @RequestBody MerchantSkuUpdateDTO dto,
            HttpServletRequest request) {
        return Result.success(productService.updateMerchantSku(
                SessionUser.currentId(request), productId, skuId, dto));
    }

    @PutMapping("/{id}/status")
    public Result<ProductStatusVO> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody ProductStatusUpdateDTO dto,
            HttpServletRequest request) {
        return Result.success(productService.updateMerchantProductStatus(
                SessionUser.currentId(request), id, dto));
    }

    @GetMapping
    public Result<MerchantProductPageVO> list(
            @RequestParam Long shopId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return Result.success(productService.listMerchantProducts(
                SessionUser.currentId(request), shopId, page, size));
    }

    @GetMapping("/{id}")
    public Result<MerchantProductDetailVO> detail(
            @PathVariable Long id,
            HttpServletRequest request) {
        return Result.success(productService.merchantDetail(SessionUser.currentId(request), id));
    }

}
