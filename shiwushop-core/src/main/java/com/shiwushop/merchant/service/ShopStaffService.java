package com.shiwushop.merchant.service;

import com.shiwushop.merchant.vo.staff.ShopStaffVO;

import java.util.List;
import java.util.Set;

public interface ShopStaffService {
    ShopStaffVO grant(Long ownerId, Long shopId, Long userId, Set<String> roles);
    ShopStaffVO revoke(Long ownerId, Long shopId, Long userId);
    List<ShopStaffVO> list(Long ownerId, Long shopId);
}
