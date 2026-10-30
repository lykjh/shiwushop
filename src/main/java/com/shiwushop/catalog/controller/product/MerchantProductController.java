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

    /** 在当前商家有权限的店铺中创建商品草稿；服务层校验店铺归属。 */
    @PostMapping
    public Result<MerchantProductVO> create(
            @Valid @RequestBody MerchantProductCreateDTO dto,
            HttpServletRequest request) {
        return Result.success(productService.createDraft(SessionUser.currentId(request), dto));
    }

    /** 修改商家自己的商品资料，包括提交的图片和展示信息。 */
    @PutMapping("/{id}")
    public Result<MerchantProductVO> update(
            @PathVariable Long id,
            @Valid @RequestBody MerchantProductUpdateDTO dto,
            HttpServletRequest request) {
        return Result.success(
                productService.updateMerchantProduct(SessionUser.currentId(request), id, dto));
    }

    /** 用请求中的完整规格选项替换该商品现有选项；服务层检查关联 SKU 的约束。 */
    @PutMapping("/{id}/options")
    public Result<List<ProductOptionVO>> replaceOptions(
            @PathVariable Long id,
            @Valid @RequestBody ProductOptionsUpdateDTO dto,
            HttpServletRequest request) {
        return Result.success(
                productService.replaceOptions(SessionUser.currentId(request), id, dto));
    }

    /** 为商家自己的商品创建一个 SKU，并由服务层校验规格值组合。 */
    @PostMapping("/{id}/skus")
    public Result<MerchantSkuVO> createSku(
            @PathVariable Long id,
            @Valid @RequestBody MerchantSkuCreateDTO dto,
            HttpServletRequest request) {
        return Result.success(
                productService.createMerchantSku(SessionUser.currentId(request), id, dto));
    }

    /** 修改指定商品下的 SKU；同时检查商品归属及 SKU 是否属于该商品。 */
    @PutMapping("/{productId}/skus/{skuId}")
    public Result<MerchantSkuVO> updateSku(
            @PathVariable Long productId,
            @PathVariable Long skuId,
            @Valid @RequestBody MerchantSkuUpdateDTO dto,
            HttpServletRequest request) {
        return Result.success(productService.updateMerchantSku(
                SessionUser.currentId(request), productId, skuId, dto));
    }

    /** 调整商品上架状态；上架条件和状态转换由商品服务校验。 */
    @PutMapping("/{id}/status")
    public Result<ProductStatusVO> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody ProductStatusUpdateDTO dto,
            HttpServletRequest request) {
        return Result.success(productService.updateMerchantProductStatus(
                SessionUser.currentId(request), id, dto));
    }

    /** 分页列出当前商家指定店铺的商品，包括商家可见的非公开状态。 */
    @GetMapping
    public Result<MerchantProductPageVO> list(
            @RequestParam Long shopId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return Result.success(productService.listMerchantProducts(
                SessionUser.currentId(request), shopId, page, size));
    }

    /** 读取当前商家一件商品的管理详情，包含其规格与 SKU 信息。 */
    @GetMapping("/{id}")
    public Result<MerchantProductDetailVO> detail(
            @PathVariable Long id,
            HttpServletRequest request) {
        return Result.success(productService.merchantDetail(SessionUser.currentId(request), id));
    }

}
