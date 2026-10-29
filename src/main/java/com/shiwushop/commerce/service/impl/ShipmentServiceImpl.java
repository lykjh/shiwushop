package com.shiwushop.commerce.service.impl;

import com.shiwushop.commerce.dto.shipment.ShipmentCreateDTO;
import com.shiwushop.commerce.vo.shipment.ShipmentVO;
import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.common.BusinessException;
import com.shiwushop.catalog.auth.MerchantShopAccess;
import com.shiwushop.commerce.entity.order.Order;
import com.shiwushop.commerce.entity.shipment.Shipment;
import com.shiwushop.commerce.entity.status.ConfirmSource;
import com.shiwushop.commerce.entity.status.OrderStatus;
import com.shiwushop.commerce.entity.status.ShipmentStatus;
import com.shiwushop.commerce.mapper.order.OrderMapper;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.commerce.mapper.shipment.ShipmentAuditMapper;
import com.shiwushop.commerce.mapper.shipment.ShipmentMapper;
import com.shiwushop.commerce.service.ShipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ShipmentServiceImpl implements ShipmentService {
    private static final int AUTO_CONFIRM_DAYS = 10;

    private final ShipmentMapper shipmentMapper;
    private final OrderMapper orderMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final ShipmentAuditMapper auditMapper;
    private final MerchantShopAccess merchantShopAccess;
    private final AccountAccess accountAccess;
    private final Clock businessClock;

    @Override
    @Transactional
    public ShipmentVO ship(Long userId, Long shopId, Long orderId, ShipmentCreateDTO request) {
        requirePositive(shopId);
        requirePositive(orderId);
        merchantShopAccess.requireOwnedForUpdate(userId, shopId, "店铺不存在");
        String carrier = clean(request == null ? null : request.carrierName(), 80);
        String trackingNo = clean(request == null ? null : request.trackingNo(), 100);

        Order order = orderMapper.selectForUpdate(orderId);
        if (order == null || !shopId.equals(order.getShopId())) {
            throw new BusinessException(404, "订单不存在");
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw new BusinessException(409, "订单尚未付款");
        }
        // 同一运单重复提交返回原结果；不同运单拒绝覆盖已发货记录。
        Shipment existing = shipmentMapper.selectByOrderIdForUpdate(orderId);
        if (existing != null) {
            if (carrier.equals(existing.getCarrierName()) && trackingNo.equals(existing.getTrackingNo())) {
                return toVO(existing);
            }
            throw new BusinessException(409, "订单已发货，运单信息不同");
        }

        if (shipmentMapper.unresolvedAfterSaleCount(orderId) > 0
                || shipmentMapper.refundedAmount(orderId).compareTo(order.getTotalAmount()) >= 0) {
            throw new BusinessException(409, "订单售后未结或已全额退款，不能发货");
        }

        LocalDateTime now = LocalDateTime.now(businessClock);
        Shipment shipment = new Shipment();
        shipment.setOrderId(orderId);
        shipment.setCarrierName(carrier);
        shipment.setTrackingNo(trackingNo);
        shipment.setStatus(ShipmentStatus.SHIPPED);
        shipment.setShippedByUserId(userId);
        shipment.setShippedAt(now);
        shipment.setAutoConfirmAt(now.plusDays(AUTO_CONFIRM_DAYS));
        shipmentMapper.insert(shipment);
        // 发货和待投递事件同事务提交，MQ 暂不可用时仍可稍后补投。
        outboxEventMapper.insertShipmentAutoConfirm(shipment.getId(), now);
        auditMapper.record(userId, "SHIPMENT_CREATED", shipment.getId(), "商家发货", now);
        return toVO(shipment);
    }

    @Override
    public ShipmentVO merchantDetail(Long userId, Long shopId, Long orderId) {
        requirePositive(shopId);
        requirePositive(orderId);
        merchantShopAccess.requireOwned(userId, shopId, "店铺不存在");
        Order order = orderMapper.selectById(orderId);
        if (order == null || !shopId.equals(order.getShopId())) {
            throw new BusinessException(404, "订单不存在");
        }
        return toVO(orderId, shipmentMapper.selectByOrderId(orderId));
    }

    @Override
    public ShipmentVO buyerDetail(Long userId, Long orderId) {
        requirePositive(orderId);
        accountAccess.checkActive(userId);
        Order order = orderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(404, "订单不存在");
        }
        return toVO(orderId, shipmentMapper.selectByOrderId(orderId));
    }

    @Override
    @Transactional
    public ShipmentVO confirmByBuyer(Long userId, Long orderId) {
        requirePositive(orderId);
        accountAccess.checkActive(userId);
        Order order = orderMapper.selectForUpdate(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(404, "订单不存在");
        }
        Shipment shipment = shipmentMapper.selectByOrderIdForUpdate(orderId);
        if (shipment == null) {
            throw new BusinessException(409, "订单尚未发货");
        }
        if (shipment.getStatus() == ShipmentStatus.SHIPPED) {
            LocalDateTime now = LocalDateTime.now(businessClock);
            if (shipmentMapper.confirm(shipment.getId(), ConfirmSource.BUYER, now) != 1) {
                return toVO(shipmentMapper.selectByOrderIdForUpdate(orderId));
            }
            auditMapper.record(userId, "SHIPMENT_CONFIRMED_BY_BUYER", shipment.getId(), "买家确认收货", now);
            shipment.setStatus(ShipmentStatus.CONFIRMED);
            shipment.setConfirmedAt(now);
            shipment.setConfirmSource(ConfirmSource.BUYER);
        }
        return toVO(shipment);
    }

    @Override
    @Transactional
    public void confirmDue(Long shipmentId) {
        // 与买家手动确认使用相同的订单锁和状态条件，避免重复确认。
        Shipment found = shipmentMapper.selectById(shipmentId);
        if (found == null) return;
        Order order = orderMapper.selectForUpdate(found.getOrderId());
        if (order == null) return;
        Shipment shipment = shipmentMapper.selectByOrderIdForUpdate(order.getId());
        LocalDateTime now = LocalDateTime.now(businessClock);
        if (shipment == null || shipment.getStatus() != ShipmentStatus.SHIPPED
                || shipment.getAutoConfirmAt().isAfter(now)) return;
        if (shipmentMapper.refundedAmount(order.getId()).compareTo(order.getTotalAmount()) >= 0) return;
        if (shipmentMapper.confirm(shipment.getId(), ConfirmSource.SYSTEM, now) == 1) {
            auditMapper.record(null, "SHIPMENT_CONFIRMED_BY_SYSTEM", shipment.getId(), "到期自动确认收货", now);
        }
    }

    @Override
    public List<Long> dueShipmentIds() {
        return shipmentMapper.selectDueIds(LocalDateTime.now(businessClock), 50);
    }

    private static String clean(String value, int maxLength) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty() || clean.length() > maxLength) {
            throw new BusinessException(400, "运单信息不能为空或超长");
        }
        return clean;
    }

    private static void requirePositive(Long id) {
        if (id == null || id <= 0) throw new BusinessException(400, "ID必须大于0");
    }

    private static ShipmentVO toVO(Long orderId, Shipment shipment) {
        return shipment == null ? new ShipmentVO(orderId, "NOT_SHIPPED", null, null,
                null, null, null, null) : toVO(shipment);
    }

    private static ShipmentVO toVO(Shipment shipment) {
        return new ShipmentVO(shipment.getOrderId(), shipment.getStatus().name(),
                shipment.getCarrierName(), shipment.getTrackingNo(), shipment.getShippedAt(),
                shipment.getAutoConfirmAt(), shipment.getConfirmedAt(),
                shipment.getConfirmSource() == null ? null : shipment.getConfirmSource().name());
    }
}
