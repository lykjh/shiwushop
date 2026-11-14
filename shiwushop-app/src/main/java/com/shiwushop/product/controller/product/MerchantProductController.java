package com.shiwushop.product.controller.product;

import com.shiwushop.product.dto.product.MerchantProductCreateDTO;
import com.shiwushop.product.dto.product.MerchantProductUpdateDTO;
import com.shiwushop.product.dto.product.ProductOptionsUpdateDTO;
import com.shiwushop.product.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.product.dto.product.ProductRevisionDTO;
import com.shiwushop.product.dto.product.ProductDisplayAttributesDTO;
import com.shiwushop.product.dto.sku.MerchantSkuCreateDTO;
import com.shiwushop.product.dto.sku.MerchantSkuUpdateDTO;
import com.shiwushop.product.dto.sku.SkuStatusUpdateDTO;
import com.shiwushop.product.dto.sku.SkuStockUpdateDTO;
import com.shiwushop.product.vo.product.MerchantProductDetailVO;
import com.shiwushop.product.vo.product.MerchantProductPageVO;
import com.shiwushop.product.vo.product.MerchantProductVO;
import com.shiwushop.product.vo.product.ProductOptionVO;
import com.shiwushop.product.vo.product.ProductStatusVO;
import com.shiwushop.product.vo.product.ProductListingVO;
import com.shiwushop.product.vo.product.DisplayAttributeVO;
import com.shiwushop.product.vo.sku.MerchantSkuVO;
import com.shiwushop.product.vo.sku.SkuStatusVO;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.product.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
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
        return Result.success(productService.createDraft(SessionUser.requireId(request), dto));
    }

    /** 修改商家自己的商品资料，包括提交的图片和展示信息。 */
    @PutMapping("/{id}")
    public Result<MerchantProductVO> update(
            @PathVariable @Positive Long id,
            @Valid @RequestBody MerchantProductUpdateDTO dto,
            HttpServletRequest request) {
        return Result.success(
                productService.updateMerchantProduct(SessionUser.requireId(request), id, dto));
    }

    /** 用请求中的完整规格选项替换该商品现有选项；服务层检查关联 SKU 的约束。 */
    @PutMapping("/{id}/options")
    public Result<List<ProductOptionVO>> replaceOptions(
            @PathVariable @Positive Long id,
            @Valid @RequestBody ProductOptionsUpdateDTO dto,
            HttpServletRequest request) {
        return Result.success(
                productService.replaceOptions(SessionUser.requireId(request), id, dto));
    }

    /** 为商家自己的商品创建一个 SKU，并由服务层校验规格值组合。 */
    @PostMapping("/{id}/skus")
    public Result<MerchantSkuVO> createSku(
            @PathVariable @Positive Long id,
            @Valid @RequestBody MerchantSkuCreateDTO dto,
            HttpServletRequest request) {
        return Result.success(
                productService.createMerchantSku(SessionUser.requireId(request), id, dto));
    }

    /** 修改指定商品下的 SKU；同时检查商品归属及 SKU 是否属于该商品。 */
    @PutMapping("/{productId}/skus/{skuId}")
    public Result<MerchantSkuVO> updateSku(
            @PathVariable @Positive Long productId,
            @PathVariable @Positive Long skuId,
            @Valid @RequestBody MerchantSkuUpdateDTO dto,
            HttpServletRequest request) {
        return Result.success(productService.updateMerchantSku(
                SessionUser.requireId(request), productId, skuId, dto));
    }

    /** 库存岗位独立调整普通库存，价格和规格不随请求改变。 */
    @PutMapping("/{productId}/skus/{skuId}/stock")
    public Result<MerchantSkuVO> updateSkuStock(@PathVariable @Positive Long productId,
                                                @PathVariable @Positive Long skuId,
                                                @Valid @RequestBody SkuStockUpdateDTO dto,
                                                HttpServletRequest request) {
        return Result.success(productService.updateSkuStock(
                SessionUser.requireId(request), productId, skuId, dto.stock()));
    }

    /** 停售会保留库存与旧订单，但新下单在SKU锁后拒绝。 */
    @PutMapping("/{productId}/skus/{skuId}/status")
    public Result<SkuStatusVO> updateSkuStatus(@PathVariable @Positive Long productId,
                                               @PathVariable @Positive Long skuId,
                                               @Valid @RequestBody SkuStatusUpdateDTO dto,
                                               HttpServletRequest request) {
        return Result.success(productService.updateSkuStatus(SessionUser.requireId(request), productId, skuId, dto.status()));
    }

    /** 调整商品上架状态；上架条件和状态转换由商品服务校验。 */
    @PutMapping("/{id}/status")
    public Result<ProductStatusVO> updateStatus(
            @PathVariable @Positive Long id,
            @Valid @RequestBody ProductStatusUpdateDTO dto,
            HttpServletRequest request) {
        return Result.success(productService.updateMerchantProductStatus(
                SessionUser.requireId(request), id, dto));
    }

    @PostMapping("/{id}/submit")
    public Result<ProductListingVO> submitListing(@PathVariable @Positive Long id,
                                                  @Valid @RequestBody ProductRevisionDTO body,
                                                  HttpServletRequest request) {
        return Result.success(productService.submitListing(SessionUser.requireId(request), id, body.revision()));
    }

    @PostMapping("/{id}/archive")
    public Result<ProductStatusVO> archive(@PathVariable @Positive Long id,
                                           @Valid @RequestBody ProductRevisionDTO body,
                                           HttpServletRequest request) {
        return Result.success(productService.archive(SessionUser.requireId(request), id, body.revision()));
    }

    @PostMapping("/{id}/restore")
    public Result<ProductStatusVO> restore(@PathVariable @Positive Long id, HttpServletRequest request) {
        return Result.success(productService.restore(SessionUser.requireId(request), id));
    }

    /** 展示参数单独替换，不改变销售规格和SKU组合。 */
    @PutMapping("/{id}/display-attributes")
    public Result<List<DisplayAttributeVO>> replaceDisplayAttributes(@PathVariable @Positive Long id,
                                                                      @Valid @RequestBody ProductDisplayAttributesDTO body,
                                                                      HttpServletRequest request) {
        return Result.success(productService.replaceDisplayAttributes(SessionUser.requireId(request), id, body));
    }

    /** 分页列出当前商家指定店铺的商品，包括商家可见的非公开状态。 */
    @GetMapping
    public Result<MerchantProductPageVO> list(
            @RequestParam @Positive Long shopId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return Result.success(productService.listMerchantProducts(
                SessionUser.requireId(request), shopId, page, size));
    }

    /** 读取当前商家一件商品的管理详情，包含其规格与 SKU 信息。 */
    @GetMapping("/{id}")
    public Result<MerchantProductDetailVO> detail(
            @PathVariable @Positive Long id,
            HttpServletRequest request) {
        return Result.success(productService.merchantDetail(SessionUser.requireId(request), id));
    }
}
