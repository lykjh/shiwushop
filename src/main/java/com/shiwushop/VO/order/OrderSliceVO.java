package com.shiwushop.vo.order;

import java.util.List;

public record OrderSliceVO(List<OrderSummaryVO> items, boolean hasMore, Long nextCursor) {}
