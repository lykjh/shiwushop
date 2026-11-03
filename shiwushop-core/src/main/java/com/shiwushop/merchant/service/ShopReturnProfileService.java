package com.shiwushop.merchant.service;

import com.shiwushop.merchant.vo.shop.ReturnDestinationSnapshot;

import com.shiwushop.merchant.dto.shop.ShopReturnProfileSaveDTO;
import com.shiwushop.merchant.vo.shop.ReturnDestinationVO;
import com.shiwushop.merchant.vo.shop.ShopReturnProfileVO;

public interface ShopReturnProfileService {
    ShopReturnProfileVO get(Long shopId, Long userId);
    ShopReturnProfileVO save(Long shopId, Long userId, ShopReturnProfileSaveDTO dto);
    ReturnDestinationSnapshot snapshotForReturn(Long shopId);
    ReturnDestinationVO decryptReturnDestination(ReturnDestinationSnapshot snapshot);
}
