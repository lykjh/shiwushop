package com.shiwushop.catalog.controller.product;

import com.shiwushop.catalog.dto.product.PublicProductQuery;
import com.shiwushop.catalog.vo.product.PublicProductDetailVO;
import com.shiwushop.catalog.vo.product.PublicProductPageVO;

import com.shiwushop.common.Result;
import com.shiwushop.catalog.cache.PublicProductCache;
import com.shiwushop.common.BusinessException;
import com.shiwushop.catalog.service.PublicProductService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final PublicProductService productService;
    private final PublicProductCache publicProductCache;

    @GetMapping
    public Result<PublicProductPageVO> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Long afterId,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        if (request.getParameterMap().containsKey("page")
                || request.getParameterMap().containsKey("includeTotal")) {
            throw new BusinessException(400, "公开商品列表不支持页码或总数查询");
        }
        return Result.success(publicProductCache.list(new PublicProductQuery(
                categoryId, brandId, shopId, minPrice, maxPrice, size, afterId)));
    }

    @GetMapping("/{id}")
    public Result<PublicProductDetailVO> publicDetail(@PathVariable Long id) {
        return Result.success(productService.publicDetail(id));
    }


}
