package com.shiwushop.aftersale.vo;

import com.shiwushop.merchant.vo.shop.ReturnDestinationVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AfterSaleVO(Long id, Long orderId, String type, String status, String reason,
                          BigDecimal refundAmount, LocalDateTime requestedAt, LocalDateTime finishedAt,
                          String reviewReason, List<Item> items, ReturnInfo returnShipment, RefundInfo refund,
                          ReturnDestinationVO returnDestination) {
    public record Item(Long orderItemId, Integer quantity, BigDecimal refundAmount) {}
    public record ReturnInfo(String carrierName, String trackingNo, LocalDateTime sentAt,
                             LocalDateTime receivedAt) {}
    public record RefundInfo(String status, String outRequestNo, LocalDateTime requestedAt,
                             LocalDateTime resolvedAt) {}
}
