package com.shiwushop.catalog.service;

import com.shiwushop.catalog.dto.shop.ShopCreateDTO;
import com.shiwushop.catalog.dto.shop.ShopProfileUpdateDTO;
import com.shiwushop.catalog.vo.shop.ShopDetailVO;
import com.shiwushop.catalog.vo.shop.ShopPageVO;
import com.shiwushop.catalog.vo.shop.ShopVO;


import java.util.List;

public interface ShopService {
    /** 管理员创建店铺并指定初始店主，返回管理视图。 */
    ShopVO create(ShopCreateDTO dto, Long adminId);

    /** 转移店铺归属，使旧店主失去后续写入权限。 */
    ShopVO changeOwner(Long shopId, Long newOwnerId);

    /** 启用或停用店铺，并更新其公开可见状态。 */
    ShopVO changeStatus(Long shopId, Integer enabled);

    /** 返回管理员可见的全部店铺资料。 */
    List<ShopVO> adminList();

    /** 按分类和页码查询启用中的公开店铺。 */
    ShopPageVO publicList(Long categoryId, int page, int size);

    /** 按 ID 查询店铺详情和公开分类；当前实现只检查店铺是否存在。 */
    ShopDetailVO publicDetail(Long id);

    /** 列出当前用户作为店主的店铺。 */
    List<ShopVO> listMine(Long userId);

    /** 店主更新自己店铺的展示资料。 */
    ShopVO updateProfile(Long shopId, Long userId, ShopProfileUpdateDTO dto);
}
