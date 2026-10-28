package com.shiwushop.service.impl;

import com.shiwushop.dto.shop.ShopCreateDTO;
import com.shiwushop.dto.shop.ShopProfileUpdateDTO;
import com.shiwushop.vo.shop.ShopDetailVO;
import com.shiwushop.vo.shop.ShopPageVO;
import com.shiwushop.vo.shop.ShopVO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountAccess;
import com.shiwushop.common.auth.MerchantShopAccess;
import com.shiwushop.util.PageSpec;
import com.shiwushop.common.auth.MediaAssetAccess;
import com.shiwushop.entity.shop.Shop;
import com.shiwushop.entity.status.AccountRole;
import com.shiwushop.entity.status.AccountStatus;
import com.shiwushop.mapper.shop.ShopMapper;
import com.shiwushop.mapper.outbox.OutboxEventMapper;
import com.shiwushop.mapper.user.UserAccountMapper;
import com.shiwushop.entity.user.UserAccount;
import com.shiwushop.service.CategoryService;
import com.shiwushop.service.ShopService;
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

    private void queueSearchRefresh(Long shopId) {
        if (outboxEventMapper.insertSearchShopRefresh(shopId) != 1) {
            throw new IllegalStateException("店铺搜索事件写入失败");
        }
    }

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

    @Override
    public List<ShopVO> adminList() {
        return shopMapper.selectList(
                new LambdaQueryWrapper<Shop>().orderByAsc(Shop::getId)
        ).stream().map(this::toVO).toList();
    }

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

    private ShopVO toVO(Shop shop) {
        return new ShopVO(shop.getId(), shop.getOwnerUserId(), shop.getName(),
                shop.getLogoMediaId(), shop.getDescription(), shop.getEnabled());
    }

}
