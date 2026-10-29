package com.shiwushop.catalog.service;

import com.shiwushop.catalog.dto.shop.ShopCreateDTO;
import com.shiwushop.catalog.dto.shop.ShopProfileUpdateDTO;
import com.shiwushop.catalog.vo.shop.ShopDetailVO;
import com.shiwushop.catalog.vo.shop.ShopPageVO;
import com.shiwushop.catalog.vo.shop.ShopVO;


import java.util.List;

public interface ShopService {
    ShopVO create(ShopCreateDTO dto, Long adminId);

    ShopVO changeOwner(Long shopId, Long newOwnerId);

    ShopVO changeStatus(Long shopId, Integer enabled);

    List<ShopVO> adminList();

    ShopPageVO publicList(Long categoryId, int page, int size);

    ShopDetailVO publicDetail(Long id);

    List<ShopVO> listMine(Long userId);

    ShopVO updateProfile(Long shopId, Long userId, ShopProfileUpdateDTO dto);
}
