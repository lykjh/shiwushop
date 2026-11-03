package com.shiwushop.aftersale.vo.timeout;

import com.shiwushop.aftersale.entity.timeout.AfterSaleTimeoutTask;
import java.util.List;

public record AfterSaleTimeoutPageVO(List<AfterSaleTimeoutTask> items, boolean hasMore, Long nextBeforeId) {}
