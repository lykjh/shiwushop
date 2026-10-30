package com.shiwushop.catalog.service.impl;

import com.shiwushop.catalog.dto.shop.ShopCreateDTO;
import com.shiwushop.catalog.dto.shop.ShopProfileUpdateDTO;
import com.shiwushop.catalog.vo.shop.ShopDetailVO;
import com.shiwushop.catalog.vo.shop.ShopPageVO;
import com.shiwushop.catalog.vo.shop.ShopVO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.catalog.auth.MerchantShopAccess;
import com.shiwushop.common.pagination.PageSpec;
import com.shiwushop.common.media.MediaAssetAccess;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.account.entity.status.AccountRole;
import com.shiwushop.account.entity.status.AccountStatus;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.account.mapper.user.UserAccountMapper;
import com.shiwushop.account.entity.user.UserAccount;
import com.shiwushop.catalog.service.CategoryService;
import com.shiwushop.catalog.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ShopServiceImpl implements ShopService {
    private final ShopMapper shopMapper;
    private final UserAccountMapper userAccountMapper;
    private final AccountAccess accountAccess;
    private final MerchantShopAccess merchantShopAccess;
    private final MediaAssetAccess mediaAssetAccess;
    private final CategoryService categoryService;
    private final OutboxEventMapper outboxEventMapper;

    /** 将店铺变化写入事务发件箱，确保搜索索引随后刷新；写入失败时中断事务。 */
    private void queueSearchRefresh(Long shopId) {
        if (outboxEventMapper.insertSearchShopRefresh(shopId) != 1) {
            throw new IllegalStateException("店铺搜索事件写入失败");
        }
    }

    /**
     * 管理员创建店铺，验证经营者账号及店铺标识图片。
     * 首次开店的普通用户在同一事务内提升为商家，并投递搜索刷新事件。
     */
    @Override
    @Transactional
    public ShopVO create(ShopCreateDTO dto, Long adminId) {
        UserAccount owner = userAccountMapper.selectById(dto.getOwnerUserId());

        if (owner == null) {
            throw new BusinessException(404,"经营者不存在");
        }

        if (owner.getStatus() != AccountStatus.ACTIVE
                || (owner.getRole() != AccountRole.USER && owner.getRole() != AccountRole.MERCHANT)) {
            throw new BusinessException(409,"该账号不能经营店铺");
        }
        if (dto.getLogoMediaId() != null) {
            mediaAssetAccess.requireReadyOwned(dto.getLogoMediaId(), adminId, "店铺标识媒体不存在或不可用");
        }

        Shop shop=new Shop();
        shop.setOwnerUserId(owner.getUserId());
        shop.setName(dto.getName().strip());
        shop.setLogoMediaId(dto.getLogoMediaId());
        shop.setDescription(dto.getDescription());
        shop.setEnabled(1);
        try {
            shopMapper.insert(shop);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409,"店铺已存在",e);
        }

        // 首次开店时提升为商家；账号角色与店铺创建同事务提交。
        if(owner.getRole() == AccountRole.USER){
            owner.setRole(AccountRole.MERCHANT);
            userAccountMapper.updateById(owner);
        }
        queueSearchRefresh(shop.getId());
        return toVO(shop);
    }

    /**
     * 锁住店铺后将其归属转给有效用户；若新店主仍是普通用户，同事务提升为商家。
     * 旧店主后续写请求会因归属检查失败而被拒绝。
     */
    @Override
    @Transactional
    public ShopVO changeOwner(Long shopId, Long newOwnerId) {

        Shop shop = shopMapper.selectForUpdate(shopId);
        if (shop == null) {
            throw new BusinessException(404, "店铺不存在");
        }

        UserAccount newOwner = userAccountMapper.selectById(newOwnerId);
        if (newOwner == null) {
            throw new BusinessException(404, "新经营者不存在");
        }

        if (newOwner.getStatus() != AccountStatus.ACTIVE
                || (newOwner.getRole() != AccountRole.USER
                && newOwner.getRole() != AccountRole.MERCHANT)) {
            throw new BusinessException(409, "该账号不能经营店铺");
        }

        shop.setOwnerUserId(newOwnerId);
        shopMapper.updateById(shop);

        if(newOwner.getRole() == AccountRole.USER) {
            newOwner.setRole(AccountRole.MERCHANT);
            userAccountMapper.updateById(newOwner);
        }
        return toVO(shop);
    }

    /** 修改店铺启用状态，并在事务内写入搜索索引刷新事件。 */
    @Override
    @Transactional
    public ShopVO changeStatus(Long shopId, Integer enabled) {

        if (shopMapper.selectById(shopId) == null) {
            throw new BusinessException(404, "店铺不存在");
        }

        shopMapper.update(null, new LambdaUpdateWrapper<Shop>()
                .eq(Shop::getId, shopId)
                .set(Shop::getEnabled, enabled));
        queueSearchRefresh(shopId);

        Shop updatedShop = shopMapper.selectById(shopId);
        return toVO(updatedShop);
    }

    /** 按 ID 返回所有店铺的管理视图，不按启用状态过滤。 */
    @Override
    public List<ShopVO> adminList() {
        return shopMapper.selectList(
                new LambdaQueryWrapper<Shop>().orderByAsc(Shop::getId)
        ).stream().map(this::toVO).toList();
    }

    /** 验证分页和可选分类参数，查询启用中店铺的一页公开列表及总数。 */
    @Override
    public ShopPageVO publicList(Long categoryId, int page, int size) {
        PageSpec pageSpec = PageSpec.of(page, size);

        if(categoryId!=null){
            if(categoryId<1){
                throw new BusinessException(400, "分类参数错误");
            }
            categoryService.detail(categoryId);
        }

        long offset = pageSpec.offset();
        return new ShopPageVO(
                shopMapper.selectPublicPage(categoryId,size,offset),
                page,size,shopMapper.countPublic(categoryId)
        );
    }

    /** 按 ID 读取店铺资料及公开分类；当前方法只校验店铺存在，不过滤停用状态。 */
    @Override
    public ShopDetailVO publicDetail(Long id) {
        if (id <= 0) {
            throw new BusinessException(400, "店铺ID必须大于0");
        }
        Shop shop = shopMapper.selectById(id);
        if (shop == null) {
            throw new BusinessException(404, "店铺不存在");
        }
        return new ShopDetailVO(
                shop.getId(),
                shop.getName(),
                shop.getLogoMediaId(),
                shop.getDescription(),
                shop.getEnabled(),
                shopMapper.selectPublicCategories(id));
    }

    /**
     * 店主修改店铺资料；更新 SQL 再次附加店主 ID 条件，避免归属检查后转店导致旧店主写入。
     * 店铺标识图片必须属于当前操作人，修改成功后刷新搜索索引。
     */
    @Override
    @Transactional
    public ShopVO updateProfile(Long shopId, Long userId, ShopProfileUpdateDTO dto) {
        merchantShopAccess.requireOwned(userId, shopId, "店铺不存在");
        if (dto.getLogoMediaId() != null) {
            mediaAssetAccess.requireReadyOwned(dto.getLogoMediaId(), userId, "店铺标识媒体不存在或不可用");
        }

        try {
            int changed=shopMapper.update(null,new LambdaUpdateWrapper<Shop>()
                    .eq(Shop::getId, shopId)
                    .eq(Shop::getOwnerUserId, userId)
                    .set(Shop::getName, dto.getName().strip())
                    .set(Shop::getLogoMediaId, dto.getLogoMediaId())
                    .set(Shop::getDescription, dto.getDescription()));

            if (changed == 0) {
                throw new BusinessException(404, "店铺不存在");
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "店铺名称已存在", e);
        }

        Shop updated = shopMapper.selectById(shopId);
        queueSearchRefresh(shopId);
        return toVO(updated);

    }

    /** 验证商家角色后列出其名下店铺，包括停用店铺。 */
    @Override
    public List<ShopVO> listMine(Long userId) {
        accountAccess.requireMerchant(userId);

        return shopMapper.selectList(new LambdaQueryWrapper<Shop>()
                        .eq(Shop::getOwnerUserId, userId)
                        .orderByAsc(Shop::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    /** 将店铺实体转换为管理接口使用的店铺视图。 */
    private ShopVO toVO(Shop shop) {
        return new ShopVO(shop.getId(), shop.getOwnerUserId(), shop.getName(),
                shop.getLogoMediaId(), shop.getDescription(), shop.getEnabled());
    }

}
