package com.shiwushop.commerce.service.impl;

import com.shiwushop.commerce.vo.order.OrderItemVO;
import com.shiwushop.commerce.vo.order.OrderSliceVO;
import com.shiwushop.commerce.vo.order.OrderDetailVO;
import com.shiwushop.commerce.vo.order.OrderSummaryVO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.catalog.auth.MerchantShopAccess;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.pagination.PageSpec;
import com.shiwushop.account.privacy.PrivacyCipher;
import com.shiwushop.commerce.entity.order.Order;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.commerce.mapper.order.OrderMapper;
import com.shiwushop.commerce.mapper.order.OrderItemMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.commerce.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ShopMapper shopMapper;
    private final AccountAccess accountAccess;
    private final MerchantShopAccess merchantShopAccess;
    private final PrivacyCipher privacyCipher;
    /** 仅向订单买家返回详情；通过归属检查后才解密下单时的收货快照。 */
    @Override
    public OrderDetailVO detail(Long userId, Long orderId) {
        accountAccess.checkActive(userId);
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getId, orderId)
                        .eq(Order::getUserId, userId));

        if(order==null){
            throw new BusinessException(404, "订单不存在");
        }
        Shop shop = order.getShopId() == null ? null : shopMapper.selectById(order.getShopId());
        return toDetailVO(order, shop);
    }

    /** 按创建时间和 ID 游标列出买家订单，并拒绝其他买家的游标。 */
    @Override
    public OrderSliceVO list(Long userId, Long afterId, int size) {
        accountAccess.checkActive(userId);
        PageSpec.of(1, size);
        Order cursor = cursor(afterId);
        if (cursor != null && !userId.equals(cursor.getUserId())) throw new BusinessException(400, "订单游标无效");
        List<OrderSummaryVO> rows = orderMapper.selectBuyerSlice(userId,
                cursor == null ? null : cursor.getCreatedAt(), afterId, size + 1);
        return slice(rows, size);
    }

    /** 核对店铺归属后按游标列出该店订单，阻止跨店游标混用。 */
    @Override
    public OrderSliceVO listMerchant(Long userId, Long shopId, Long afterId, int size) {
        SessionUser.requireId(userId);
        if (shopId == null || shopId <= 0) {
            throw new BusinessException(400, "店铺或分页参数错误");
        }
        PageSpec.of(1, size, "店铺或分页参数错误");
        merchantShopAccess.requireOwned(userId, shopId, "店铺不存在");
        Order cursor = cursor(afterId);
        if (cursor != null && !shopId.equals(cursor.getShopId())) throw new BusinessException(400, "订单游标无效");
        List<OrderSummaryVO> rows = orderMapper.selectMerchantSlice(shopId,
                cursor == null ? null : cursor.getCreatedAt(), afterId, size + 1);
        return slice(rows, size);
    }

    /** 解析分页游标；非法或不存在的订单 ID 直接返回参数错误。 */
    private Order cursor(Long afterId) {
        if (afterId == null) return null;
        if (afterId <= 0) throw new BusinessException(400, "订单游标无效");
        Order order = orderMapper.selectById(afterId);
        if (order == null) throw new BusinessException(400, "订单游标无效");
        return order;
    }

    /** 利用多取的一行判断是否有下一页，并生成下一页游标。 */
    private OrderSliceVO slice(List<OrderSummaryVO> rows, int size) {
        boolean hasMore = rows.size() > size;
        List<OrderSummaryVO> items = hasMore ? rows.subList(0, size) : rows;
        return new OrderSliceVO(items, hasMore, hasMore ? items.getLast().getId() : null);
    }

    /** 仅向当前店主返回本店订单详情，包含下单时的收货信息。 */
    @Override
    public OrderDetailVO detailMerchant(Long userId, Long shopId, Long orderId) {
        SessionUser.requireId(userId);
        if (shopId == null || shopId <= 0 || orderId == null || orderId <= 0) {
            throw new BusinessException(400, "店铺或订单 ID 错误");
        }
        Shop shop = merchantShopAccess.requireOwned(userId, shopId, "店铺不存在");
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getShopId, shopId));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        return toDetailVO(order, shop);
    }

    /** 收货信息仅在通过买家或店铺归属校验后解密，订单中读取的是下单时快照。 */
    private OrderDetailVO toDetailVO(Order order, Shop shop) {
        Long buyerId = order.getUserId();
        int version = order.getShippingKeyVersion();
        String consignee = privacyCipher.decrypt(buyerId, "orders:consignee", version, order.getConsigneeCiphertext());
        String phone = privacyCipher.decrypt(buyerId, "orders:phone", version, order.getPhoneCiphertext());
        String detail = privacyCipher.decrypt(buyerId, "orders:detail", version, order.getDetailCiphertext());
        return new OrderDetailVO(order.getId(), order.getShopId(),
                shop == null ? null : shop.getName(), order.getStatus().name(), order.getTotalAmount(),
                new OrderDetailVO.AddressSnapshot(consignee, phone,
                        order.getProvince() + order.getCity() + order.getDistrict() + detail),
                itemsForOrder(order.getId()));
    }

    /** 读取订单明细并按成交单价与数量计算每项小计。 */
    private List<OrderItemVO> itemsForOrder(Long orderId) {
        return orderItemMapper.selectForOrder(orderId).stream()
                .map(item -> new OrderItemVO(item.getId(), item.getProductId(), item.getSkuId(),
                        item.getProductName(), item.getSkuSpec(), item.getQuantity(),
                        item.getUnitPrice(), item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))))
                .toList();
    }

}
