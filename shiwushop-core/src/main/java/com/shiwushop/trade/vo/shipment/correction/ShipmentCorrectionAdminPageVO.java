package com.shiwushop.trade.vo.shipment.correction;

import com.shiwushop.trade.entity.shipment.ShipmentCorrection;

import java.util.List;

public record ShipmentCorrectionAdminPageVO(
    /** 当前对象的明细列表。 */
    List<ShipmentCorrection> items,
    /** 是否还有下一页。 */
    boolean hasMore,
    /** 下一页查询游标。 */
    Long nextCursor
) {
}
