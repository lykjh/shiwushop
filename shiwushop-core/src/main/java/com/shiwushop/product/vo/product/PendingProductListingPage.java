package com.shiwushop.product.vo.product;

import java.time.LocalDateTime;
import java.util.List;

public record PendingProductListingPage(List<Item> items, int page, int size, long total) {
    public record Item(Long productId, Long shopId, String name, Long revision, LocalDateTime submittedAt) {}

    public record Detail(MerchantProductDetailVO product, LocalDateTime submittedAt) {}
}
