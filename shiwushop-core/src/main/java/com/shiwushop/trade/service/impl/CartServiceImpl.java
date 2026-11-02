package com.shiwushop.trade.service.impl;

import com.shiwushop.trade.dto.cart.CartAddDTO;
import com.shiwushop.trade.vo.cart.CartItemVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.trade.entity.cart.CartItem;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.product.Sku;
import com.shiwushop.trade.mapper.cart.CartItemMapper;
import com.shiwushop.trade.service.CartService;
import lombok.RequiredArgsConstructor;
import com.shiwushop.product.service.TradingProductService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.*;

/** 只维护购物车内容；下单事务由 CheckoutServiceImpl 负责。 */
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {
    private final CartItemMapper cartItemMapper;
    private final AccountAccess accountAccess;
    private final TradingProductService products;

    /** 校验账号和 SKU 可售性后新增购物车项；重复 SKU 返回冲突，不会叠加数量。 */
    @Override
    public CartItemVO add(Long userId, CartAddDTO dto) {
        accountAccess.checkActive(userId);
        Sku sku = products.findSku(dto.getSkuId());
        if (sku == null) {
            throw new BusinessException(404, "SKU不存在");
        }
        String reason = products.unavailableReason(sku, products.findProduct(sku.getProductId()), dto.getQuantity());
        if (reason != null) {
            throw new BusinessException(409, reason);
        }
        CartItem item = new CartItem();
        item.setUserId(userId);
        item.setSkuId(dto.getSkuId());
        item.setQuantity(dto.getQuantity());
        try {
            cartItemMapper.insert(item);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "商品已在购物车中", e);
        }
        return toVO(item);
    }

    /** 批量读取购物车关联的商品、店铺、分类和规格，逐项标明当前不可购买原因。 */
    @Override
    public List<CartItemVO> list(Long userId) {
        accountAccess.checkActive(userId);
        List<CartItem> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId).orderByAsc(CartItem::getId));
        if (items.isEmpty()) return List.of();

        Map<Long, Integer> quantities = new LinkedHashMap<>();
        items.forEach(item -> quantities.put(item.getSkuId(), item.getQuantity()));
        Map<Long, TradingProductService.CartProduct> snapshots = products.cartProducts(quantities);
        return items.stream().map(item -> {
            TradingProductService.CartProduct snapshot = snapshots.get(item.getSkuId());
            Sku sku = snapshot.sku();
            Product product = snapshot.product();
            String reason = snapshot.unavailableReason();
            return new CartItemVO(item.getId(), item.getSkuId(),
                    product == null ? null : product.getId(), product == null ? null : product.getName(),
                    snapshot.optionNames(), sku == null ? null : sku.getPrice(), item.getQuantity(), reason == null, reason);
        }).toList();
    }

    /** 仅修改当前用户的购物车项；写入行数为零时报告该项已不存在。 */
    @Override
    public CartItemVO updateQuantity(Long userId, Long id, Integer quantity) {
        CartItem item = ownedItem(userId, id);
        item.setQuantity(quantity);
        if (cartItemMapper.updateById(item) != 1) {
            throw new BusinessException(404, "购物车商品不存在");
        }
        return toVO(item);
    }

    /** 校验归属后删除购物车项；已被并发删除时返回不存在。 */
    @Override
    public void delete(Long userId, Long id) {
        ownedItem(userId, id);
        if (cartItemMapper.deleteById(id) != 1) {
            throw new BusinessException(404, "购物车商品不存在");
        }
    }

    /** 按用户和条目 ID 联合查询，防止操作其他用户的购物车。 */
    private CartItem ownedItem(Long userId, Long id) {
        accountAccess.checkActive(userId);
        CartItem item = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getId, id)
                .eq(CartItem::getUserId, userId));
        if (item == null) {
            throw new BusinessException(404, "购物车商品不存在");
        }
        return item;
    }

    /** 将新建或修改的单项转换为响应，并重新计算当前可售状态。 */
    private CartItemVO toVO(CartItem item) {
        Sku sku = products.findSku(item.getSkuId());
        Product product = sku == null ? null : products.findProduct(sku.getProductId());
        String reason = products.unavailableReason(sku, product, item.getQuantity());
        List<String> values = sku == null ? List.of() : products.optionNames(sku.getId());
        return new CartItemVO(item.getId(), item.getSkuId(),
                product == null ? null : product.getId(),
                product == null ? null : product.getName(), values,
                sku == null ? null : sku.getPrice(), item.getQuantity(), reason == null, reason);
    }


}
