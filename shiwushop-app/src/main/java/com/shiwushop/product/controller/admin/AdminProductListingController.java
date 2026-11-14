package com.shiwushop.product.controller.admin;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.AdminAuth;
import com.shiwushop.common.media.service.MediaReadService;
import com.shiwushop.product.dto.product.ProductListingDecisionDTO;
import com.shiwushop.product.service.ProductService;
import com.shiwushop.product.vo.product.ProductListingVO;
import com.shiwushop.product.vo.product.PendingProductListingPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/products")
@RequiredArgsConstructor
public class AdminProductListingController {
    private final ProductService products;
    private final AdminAuth adminAuth;
    private final MediaReadService media;

    @GetMapping("/pending")
    public Result<PendingProductListingPage> pending(@RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      HttpServletRequest request) {
        adminAuth.requireAdmin(request);
        return Result.success(products.listPendingListings(page, size));
    }

    @GetMapping("/{id}/listing")
    public Result<PendingProductListingPage.Detail> detail(@PathVariable @Positive Long id,
                                                           HttpServletRequest request) {
        adminAuth.requireAdmin(request);
        return Result.success(products.pendingListingDetail(id));
    }

    @GetMapping("/{id}/listing/images/{mediaId}")
    public ResponseEntity<Resource> image(@PathVariable @Positive Long id, @PathVariable @Positive Long mediaId,
                                           @RequestParam @PositiveOrZero Long revision, HttpServletRequest request) {
        Long reviewerId = adminAuth.requireAdmin(request);
        products.requirePendingListingImage(id, revision, mediaId);
        var image = media.readProductImageForReview(mediaId, reviewerId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.mimeType())).body(image.resource());
    }

    @PostMapping("/{id}/decision")
    public Result<ProductListingVO> decide(@PathVariable @Positive Long id,
                                           @Valid @RequestBody ProductListingDecisionDTO body,
                                           HttpServletRequest request) {
        return Result.success(products.decideListing(adminAuth.requireAdmin(request), id, body));
    }
}
