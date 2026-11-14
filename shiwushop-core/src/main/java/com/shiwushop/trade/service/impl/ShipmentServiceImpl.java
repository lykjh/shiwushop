package com.shiwushop.trade.service.impl;

import com.shiwushop.trade.dto.shipment.ShipmentCreateDTO;
import com.shiwushop.trade.dto.shipment.ShipmentPackageCreateDTO;
import com.shiwushop.trade.vo.shipment.ShipmentVO;
import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.entity.order.OrderItem;
import com.shiwushop.trade.entity.shipment.Shipment;
import com.shiwushop.trade.entity.status.ConfirmSource;
import com.shiwushop.trade.entity.status.OrderStatus;
import com.shiwushop.trade.entity.status.ShipmentStatus;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.trade.mapper.order.OrderItemMapper;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentAuditMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentItemMapper;
import com.shiwushop.trade.service.ShipmentService;
import com.shiwushop.member.service.MemberRewardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ShipmentServiceImpl implements ShipmentService {
    /** 包裹发货后自动确认收货的期限，单位为天。 */
    private static final int AUTO_CONFIRM_DAYS = 10;

    private final ShipmentMapper shipmentMapper;
    private final ShipmentItemMapper shipmentItems;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItems;
    private final OutboxEventMapper outboxEventMapper;
    private final ShipmentAuditMapper auditMapper;
    private final MerchantShopAccess merchantShopAccess;
    private final AccountAccess accountAccess;
    private final Clock businessClock;
    private final MemberRewardService memberRewards;

    /** 旧入口只发一个包裹；重复相同运单返回原记录，多包裹改走指定件数入口。 */
    @Override
    @Transactional
    public ShipmentVO ship(Long userId, Long shopId, Long orderId, ShipmentCreateDTO request) {
        merchantShopAccess.requireOwnedForUpdate(userId, shopId, "店铺不存在");
        Order order = orderMapper.selectForUpdate(orderId);
        requireShippable(order, shopId);
        List<Shipment> existing = shipmentMapper.selectAllByOrderId(orderId);
        if (!existing.isEmpty()) {
            if (existing.size() == 1 && request.carrierName().equals(existing.getFirst().getCarrierName())
                    && request.trackingNo().equals(existing.getFirst().getTrackingNo()))
                return toVO(existing.getFirst());
            throw new BusinessException(409, "订单已有包裹，请使用逐包发货入口");
        }
        List<ShipmentPackageCreateDTO.Item> lines = orderItems.selectForOrder(orderId).stream()
                .map(item -> new ShipmentPackageCreateDTO.Item(item.getId(), availableToShip(item)))
                .filter(item -> item.quantity() > 0).toList();
        if (lines.isEmpty())
            throw new BusinessException(409, "订单没有可发货件数");
        return createPackage(userId, orderId, "LEGACY", request.carrierName(), request.trackingNo(), lines);
    }

    /** 订单锁串行化发货件数和幂等键，同一请求不再占用第二次件数。 */
    @Override
    @Transactional
    public ShipmentVO shipPackage(Long userId, Long shopId, Long orderId, ShipmentPackageCreateDTO request) {
        merchantShopAccess.requireOwnedForUpdate(userId, shopId, "店铺不存在");
        Order order = orderMapper.selectForUpdate(orderId);
        requireShippable(order, shopId);
        Shipment existing = shipmentMapper.selectByRequestKeyForUpdate(orderId, request.requestKey());
        if (existing != null) {
            List<ShipmentVO.Item> saved = shipmentItems.selectForShipment(existing.getId()).stream()
                    .map(item -> new ShipmentVO.Item(item.getOrderItemId(), item.getQuantity())).toList();
            List<ShipmentVO.Item> supplied = request.items().stream()
                    .map(item -> new ShipmentVO.Item(item.orderItemId(), item.quantity()))
                    .sorted((a, b) -> a.orderItemId().compareTo(b.orderItemId())).toList();
            if (existing.getCarrierName().equals(request.carrierName())
                    && existing.getTrackingNo().equals(request.trackingNo()) && saved.equals(supplied))
                return toVO(existing);
            throw new BusinessException(409, "发货幂等键对应的内容不同");
        }
        Set<Long> seen = new HashSet<>();
        for (ShipmentPackageCreateDTO.Item line : request.items()) {
            if (line == null || line.orderItemId() == null || line.quantity() <= 0 || !seen.add(line.orderItemId()))
                throw new BusinessException(400, "发货明细无效或重复");
            OrderItem original = orderItems.selectById(line.orderItemId());
            if (original == null || !orderId.equals(original.getOrderId()))
                throw new BusinessException(404, "订单商品不存在");
            if (line.quantity() > availableToShip(original))
                throw new BusinessException(409, "发货件数超过可发件数");
        }
        return createPackage(userId, orderId, request.requestKey(), request.carrierName(),
                request.trackingNo(), request.items());
    }

    private void requireShippable(Order order, Long shopId) {
        if (order == null || !shopId.equals(order.getShopId()))
            throw new BusinessException(404, "订单不存在");
        if ("STOPPED".equals(order.getFulfillmentState()))
            throw new BusinessException(409, "订单履约已停止，不能发货");
        if (!"READY".equals(order.getFulfillmentState()))
            throw new BusinessException(409, "订单尚未具备发货资格");
        if (order.getStatus() != OrderStatus.PAID)
            throw new BusinessException(409, "订单尚未付款");
        if (shipmentMapper.unresolvedAllocationCount(order.getId()) > 0
                || shipmentMapper.unresolvedAfterSaleCount(order.getId()) > 0)
            throw new BusinessException(409, "包裹件数待核实或售后未结，不能发货");
    }

    private int availableToShip(OrderItem item) {
        return item.getQuantity() - shipmentItems.shippedQuantity(item.getId())
                - shipmentItems.refundedUnshippedQuantity(item.getId());
    }

    private ShipmentVO createPackage(Long userId, Long orderId, String key, String carrier, String trackingNo,
                                     List<ShipmentPackageCreateDTO.Item> lines) {
        LocalDateTime now = LocalDateTime.now(businessClock);
        Shipment shipment = new Shipment();
        shipment.setOrderId(orderId);
        shipment.setRequestKey(key);
        shipment.setAllocationState("COMPLETE");
        shipment.setCarrierName(carrier);
        shipment.setTrackingNo(trackingNo);
        shipment.setStatus(ShipmentStatus.SHIPPED);
        shipment.setShippedByUserId(userId);
        shipment.setShippedAt(now);
        shipment.setAutoConfirmAt(now.plusDays(AUTO_CONFIRM_DAYS));
        shipmentMapper.insert(shipment);
        for (ShipmentPackageCreateDTO.Item line : lines)
            shipmentItems.insert(shipment.getId(), line.orderItemId(), line.quantity());
        outboxEventMapper.insertShipmentAutoConfirm(shipment.getId(), shipment.getAutoConfirmAt().minusHours(23));
        auditMapper.record(userId, "SHIPMENT_CREATED", shipment.getId(), "商家发货", now);
        BusinessEventLog.committed("trade", "shipment_created", "shipment", shipment.getId());
        return toVO(shipment);
    }

    /** 核对店铺归属后查看运单；尚未发货时返回未发货状态。 */
    @Override
    public ShipmentVO merchantDetail(Long userId, Long shopId, Long orderId) {
        merchantShopAccess.requireOwned(userId, shopId, "店铺不存在");
        Order order = orderMapper.selectById(orderId);
        if (order == null || !shopId.equals(order.getShopId())) {
            throw new BusinessException(404, "订单不存在");
        }
        List<Shipment> rows = shipmentMapper.selectAllByOrderId(orderId);
        if (rows.size() > 1)
            throw new BusinessException(409, "订单有多个包裹，请使用包裹列表");
        return toVO(orderId, rows.isEmpty() ? null : rows.getFirst());
    }

    @Override
    public List<ShipmentVO> merchantPackages(Long userId, Long shopId, Long orderId) {
        merchantShopAccess.requireOwned(userId, shopId, "店铺不存在");
        Order order = orderMapper.selectById(orderId);
        if (order == null || !shopId.equals(order.getShopId()))
            throw new BusinessException(404, "订单不存在");
        return shipmentMapper.selectAllByOrderId(orderId).stream().map(this::toVO).toList();
    }

    /** 核对买家与订单归属后查看运单，避免泄露其他用户的物流信息。 */
    @Override
    public ShipmentVO buyerDetail(Long userId, Long orderId) {
        accountAccess.checkActive(userId);
        Order order = orderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(404, "订单不存在");
        }
        List<Shipment> rows = shipmentMapper.selectAllByOrderId(orderId);
        if (rows.size() > 1)
            throw new BusinessException(409, "订单有多个包裹，请使用包裹列表");
        return toVO(orderId, rows.isEmpty() ? null : rows.getFirst());
    }

    @Override
    public List<ShipmentVO> buyerPackages(Long userId, Long orderId) {
        accountAccess.checkActive(userId);
        Order order = orderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId()))
            throw new BusinessException(404, "订单不存在");
        return shipmentMapper.selectAllByOrderId(orderId).stream().map(this::toVO).toList();
    }

    /** 锁定订单和运单后确认收货；重复确认保持原结果并只记录一次审计。 */
    @Override
    @Transactional
    public ShipmentVO confirmByBuyer(Long userId, Long orderId) {
        accountAccess.checkActive(userId);
        Order order = orderMapper.selectForUpdate(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(404, "订单不存在");
        }
        List<Shipment> rows = shipmentMapper.selectAllByOrderId(orderId);
        if (rows.isEmpty())
            throw new BusinessException(409, "订单尚未发货");
        if (rows.size() > 1)
            throw new BusinessException(409, "订单有多个包裹，请指定包裹确认");
        Shipment shipment = shipmentMapper.selectByIdForUpdate(rows.getFirst().getId());
        return confirmLocked(order, shipment, userId);
    }

    @Override
    @Transactional
    public ShipmentVO confirmPackageByBuyer(Long userId, Long orderId, Long shipmentId) {
        accountAccess.checkActive(userId);
        Order order = orderMapper.selectForUpdate(orderId);
        if (order == null || !userId.equals(order.getUserId()))
            throw new BusinessException(404, "订单不存在");
        Shipment shipment = shipmentMapper.selectByIdForUpdate(shipmentId);
        if (shipment == null || !orderId.equals(shipment.getOrderId()))
            throw new BusinessException(404, "包裹不存在");
        return confirmLocked(order, shipment, userId);
    }

    private ShipmentVO confirmLocked(Order order, Shipment shipment, Long userId) {
        if (shipment.getStatus() == ShipmentStatus.SHIPPED) {
            if (!"COMPLETE".equals(shipment.getAllocationState())
                    || shipmentItems.packageEffectiveQuantity(shipment.getId()) <= 0)
                throw new BusinessException(409, "包裹件数待核实或已全部退款");
            LocalDateTime now = LocalDateTime.now(businessClock);
            if (shipmentMapper.confirm(shipment.getId(), ConfirmSource.BUYER, now) != 1) {
                return toVO(shipmentMapper.selectByIdForUpdate(shipment.getId()));
            }
            auditMapper.record(userId, "SHIPMENT_CONFIRMED_BY_BUYER", shipment.getId(), "买家确认收货", now);
            shipment.setStatus(ShipmentStatus.CONFIRMED);
            shipment.setConfirmedAt(now);
            shipment.setConfirmSource(ConfirmSource.BUYER);
            maybeCompleteReceipt(order);
            BusinessEventLog.committed("trade", "shipment_confirmed_by_buyer", "shipment", shipment.getId());
        }
        return toVO(shipment);
    }

    /** 到期自动确认未退款的已发货订单；与买家确认使用相同锁顺序防重复。 */
    @Override
    @Transactional
    public boolean confirmDue(Long shipmentId) {
        // 与买家手动确认使用相同的订单锁和状态条件，避免重复确认。
        Shipment found = shipmentMapper.selectById(shipmentId);
        if (found == null)
            return true;
        Order order = orderMapper.selectForUpdate(found.getOrderId());
        if (order == null)
            return true;
        Shipment shipment = shipmentMapper.selectByIdForUpdate(shipmentId);
        LocalDateTime now = LocalDateTime.now(businessClock);
        if (shipment == null || !order.getId().equals(shipment.getOrderId())
                || shipment.getStatus() != ShipmentStatus.SHIPPED)
            return true;
        if ("STOPPED".equals(order.getFulfillmentState())
                || !"COMPLETE".equals(shipment.getAllocationState())
                || shipmentItems.packageEffectiveQuantity(shipmentId) <= 0)
            return true;
        if (shipment.getAutoConfirmAt().isAfter(now))
            return false;
        if (shipmentMapper.confirm(shipment.getId(), ConfirmSource.SYSTEM, now) == 1) {
            maybeCompleteReceipt(order);
            auditMapper.record(null, "SHIPMENT_CONFIRMED_BY_SYSTEM", shipment.getId(), "到期自动确认收货", now);
            BusinessEventLog.committed("trade", "shipment_confirmed_by_system", "shipment", shipment.getId());
        }
        return true;
    }

    /** 仅全部有效件数收齐时记录一次订单事实；非现金明细不生成消费成长资格。 */
    private void maybeCompleteReceipt(Order order) {
        if (order.getFullyReceivedAt() != null || shipmentMapper.unresolvedAllocationCount(order.getId()) > 0)
            return;
        int required = shipmentItems.requiredQuantity(order.getId());
        if (required <= 0 || shipmentItems.confirmedEffectiveQuantity(order.getId()) != required)
            return;
        LocalDateTime at = shipmentItems.lastEffectiveConfirmation(order.getId());
        if (at != null && orderMapper.markFullyReceived(order.getId(), at) == 1) {
            order.setFullyReceivedAt(at);
            if ("CASH".equals(order.getSettlementKind()))
                memberRewards.receiptConfirmed(order.getId(), at);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileReceiptAfterRefund(Long orderId) {
        Order order = orderMapper.selectForUpdate(orderId);
        if (order != null)
            maybeCompleteReceipt(order);
    }

    /** 每轮最多取 50 个达到自动确认时间的运单 ID，交给后台逐个处理。 */
    @Override
    public List<Long> dueShipmentIds() {
        return shipmentMapper.selectDueIds(LocalDateTime.now(businessClock), 50);
    }

    /** 统一转换查询响应；无运单时显式给出 NOT_SHIPPED。 */
    private ShipmentVO toVO(Long orderId, Shipment shipment) {
        return shipment == null ? new ShipmentVO(null, orderId, "NOT_SHIPPED", null, null,
                null, null, null, null, List.of()) : toVO(shipment);
    }

    /** 将运单状态、追踪信息和确认来源转换为对外响应。 */
    private ShipmentVO toVO(Shipment shipment) {
        List<ShipmentVO.Item> lines = shipmentItems.selectForShipment(shipment.getId()).stream()
                .map(item -> new ShipmentVO.Item(item.getOrderItemId(), item.getQuantity())).toList();
        return new ShipmentVO(shipment.getId(), shipment.getOrderId(), shipment.getStatus().name(),
                shipment.getCarrierName(), shipment.getTrackingNo(), shipment.getShippedAt(),
                shipment.getAutoConfirmAt(), shipment.getConfirmedAt(),
                shipment.getConfirmSource() == null ? null : shipment.getConfirmSource().name(), lines);
    }
}
