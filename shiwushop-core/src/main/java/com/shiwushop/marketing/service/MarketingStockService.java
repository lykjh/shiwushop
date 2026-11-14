package com.shiwushop.marketing.service;

import com.shiwushop.marketing.dto.StockTransfer;
import com.shiwushop.marketing.vo.StockPoolVO;
import com.shiwushop.marketing.vo.StockEntryPage;

import java.util.List;

public interface MarketingStockService {
    List<StockPoolVO> pools(Long actor, Long shop, Long activityId);

    StockEntryPage entries(Long actor, Long shop, Long activitySkuId, Long afterId, int size);

    StockPoolVO.Transfer transfer(Long actor, Long shop, Long activitySkuId, String key, StockTransfer request);
}
