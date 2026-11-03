package com.shiwushop.trade.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.trade.dto.shipment.correction.ShipmentCorrectionRequestDTO;
import com.shiwushop.trade.dto.shipment.correction.ShipmentCorrectionDecisionDTO;
import com.shiwushop.trade.dto.shipment.correction.ShipmentCorrectionStatus;
import com.shiwushop.trade.vo.shipment.correction.ShipmentCorrectionHistoryPageVO;
import com.shiwushop.trade.vo.shipment.correction.ShipmentCorrectionAdminPageVO;
import com.shiwushop.user.service.AccountAccess;
import java.util.List;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.entity.shipment.Shipment;
import com.shiwushop.trade.entity.shipment.ShipmentCorrection;
import com.shiwushop.trade.entity.status.ShipmentStatus;
import com.shiwushop.trade.entity.status.OrderStatus;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentCorrectionMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentMapper;
import com.shiwushop.trade.service.ShipmentCorrectionService;
import com.shiwushop.trade.vo.shipment.correction.ShipmentCorrectionRequestVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ShipmentCorrectionServiceImpl implements ShipmentCorrectionService {
    private final MerchantShopAccess shops;
    private final ShopService shopService;
    private final OrderMapper orders;
    private final ShipmentMapper shipments;
    private final ShipmentCorrectionMapper corrections;
    private final Clock businessClock;
    private final AccountAccess accounts;

    /** 按店铺、订单、运单、申请锁序提交，重复相同申请返回原申请。 */
    @Override
    @Transactional
    public ShipmentCorrectionRequestVO request(Long userId, Long shopId, Long orderId, ShipmentCorrectionRequestDTO body) {
        shops.requireOwnedForUpdate(userId, shopId, "店铺不存在");
        Order order = orders.selectForUpdate(orderId);
        if (order == null || !shopId.equals(order.getShopId())) throw new BusinessException(404, "订单不存在");
        Shipment shipment = shipments.selectByOrderIdForUpdate(orderId);
        if (shipment == null || shipment.getStatus() != ShipmentStatus.SHIPPED)
            throw new BusinessException(409, "运单不可更正");
        int version = corrections.version(shipment.getId());
        ShipmentCorrection pending = corrections.pendingForUpdate(shipment.getId());
        if (pending != null) {
            if (pending.getOriginalVersion().equals(body.originalVersion())
                    && pending.getNewCarrierName().equals(body.carrierName())
                    && pending.getNewTrackingNo().equals(body.trackingNo())
                    && pending.getReason().equals(body.reason()))
                return new ShipmentCorrectionRequestVO(pending.getId(), pending.getStatus());
            throw new BusinessException(409, "已有待审核的更正申请");
        }
        if (version != body.originalVersion()) throw new BusinessException(409, "运单版本已变化");
        if (shipment.getCarrierName().equals(body.carrierName()) && shipment.getTrackingNo().equals(body.trackingNo()))
            throw new BusinessException(409, "运单信息未变化");
        ShipmentCorrection correction = new ShipmentCorrection();
        correction.setShipmentId(shipment.getId()); correction.setOrderId(orderId); correction.setShopId(shopId);
        correction.setOriginalVersion(version); correction.setOldCarrierName(shipment.getCarrierName());
        correction.setOldTrackingNo(shipment.getTrackingNo()); correction.setNewCarrierName(body.carrierName());
        correction.setNewTrackingNo(body.trackingNo()); correction.setReason(body.reason());
        correction.setRequestedBy(userId); correction.setRequestedAt(LocalDateTime.now(businessClock));
        corrections.insert(correction);
        BusinessEventLog.committed("trade", "shipment_correction_requested", "shipment_correction", correction.getId());
        return new ShipmentCorrectionRequestVO(correction.getId(), "PENDING");
    }

    /** 审核前按店铺、订单、运单、申请锁序核对当前店主及运单版本。 */
    @Override
    @Transactional
    public ShipmentCorrectionRequestVO decide(Long userId, Long id, ShipmentCorrectionDecisionDTO body) {
        ShipmentCorrection found = corrections.find(id);
        if (found == null) throw new BusinessException(404, "更正申请不存在");
        String decision = body.approved() ? "APPROVED" : "REJECTED";
        if (!found.getStatus().equals("PENDING")) {
            if (found.getStatus().equals(decision)) return new ShipmentCorrectionRequestVO(id, decision);
            throw new BusinessException(409, "申请已审核");
        }
        Shop shop = shopService.lockById(found.getShopId());
        Order order = orders.selectForUpdate(found.getOrderId());
        if (order == null || !found.getShopId().equals(order.getShopId()))
            throw new BusinessException(404, "订单不存在");
        Shipment shipment = shipments.selectByOrderIdForUpdate(order.getId());
        ShipmentCorrection current = corrections.findForUpdate(id);
        if (!current.getStatus().equals("PENDING")) {
            if (current.getStatus().equals(decision)) return new ShipmentCorrectionRequestVO(id, decision);
            throw new BusinessException(409, "申请已审核");
        }
        if (body.approved() && (!current.getRequestedBy().equals(shop.getOwnerUserId())
                || order.getStatus() != OrderStatus.PAID || shipment == null || !shipment.getId().equals(current.getShipmentId())
                || shipment.getStatus() != ShipmentStatus.SHIPPED
                || corrections.version(shipment.getId()) != current.getOriginalVersion()))
            throw new BusinessException(409, "运单状态或版本已变化");
        if (body.approved() && corrections.apply(shipment.getId(), current.getOriginalVersion(),
                current.getNewCarrierName(), current.getNewTrackingNo()) != 1)
            throw new BusinessException(409, "运单状态或版本已变化");
        corrections.decide(id, decision, userId, LocalDateTime.now(businessClock), body.remark());
        BusinessEventLog.committed("trade", "shipment_correction_decided", "shipment_correction", id);
        return new ShipmentCorrectionRequestVO(id, decision);
    }

    /** 买家只能读取本人订单的已生效历史。 */
    @Override
    @Transactional(readOnly = true)
    public ShipmentCorrectionHistoryPageVO buyerHistory(Long userId, Long orderId) {
        accounts.checkActive(userId);
        Order order = orders.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) throw new BusinessException(404, "订单不存在");
        return history(orderId);
    }

    /** 店主只能读取当前店铺订单的已生效历史。 */
    @Override
    @Transactional(readOnly = true)
    public ShipmentCorrectionHistoryPageVO merchantHistory(Long userId, Long shopId, Long orderId) {
        shops.requireOwned(userId, shopId, "店铺不存在");
        Order order = orders.selectById(orderId);
        if (order == null || !shopId.equals(order.getShopId())) throw new BusinessException(404, "订单不存在");
        return history(orderId);
    }

    /** 当前店主仅可查看本店本订单申请的状态。 */
    @Override
    public ShipmentCorrectionRequestVO merchantRequest(Long userId, Long shopId, Long orderId, Long correctionId) {
        shops.requireOwned(userId, shopId, "店铺不存在");
        ShipmentCorrection correction = corrections.find(correctionId);
        if (correction == null || !shopId.equals(correction.getShopId()) || !orderId.equals(correction.getOrderId()))
            throw new BusinessException(404, "更正申请不存在");
        return new ShipmentCorrectionRequestVO(correction.getId(), correction.getStatus());
    }

    /** 一致快照内返回版本与已批准历史，不包含内部审批备注。 */
    private ShipmentCorrectionHistoryPageVO history(Long orderId) {
        Shipment shipment = shipments.selectByOrderId(orderId);
        if (shipment == null) throw new BusinessException(404, "运单不存在");
        return new ShipmentCorrectionHistoryPageVO(corrections.version(shipment.getId()), corrections.history(orderId));
    }

    /** 管理员按状态和 ID 游标读取更正申请。 */
    @Override
    public ShipmentCorrectionAdminPageVO adminList(ShipmentCorrectionStatus status, Long afterId, int size) {
        List<ShipmentCorrection> rows = corrections.list(status, afterId, size + 1);
        boolean hasMore = rows.size() > size;
        List<ShipmentCorrection> items = hasMore ? List.copyOf(rows.subList(0, size)) : rows;
        return new ShipmentCorrectionAdminPageVO(items, hasMore, hasMore ? items.getLast().getId() : null);
    }
}
