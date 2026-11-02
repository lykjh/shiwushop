package com.shiwushop.product.vo.product;

import java.util.List;

public record PublicProductPageVO(List<PublicProductVO> items, int size,
                                  boolean hasMore, Long nextCursor) {
}
