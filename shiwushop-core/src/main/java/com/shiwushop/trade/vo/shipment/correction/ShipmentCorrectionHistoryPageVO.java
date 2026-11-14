package com.shiwushop.trade.vo.shipment.correction;

import java.util.List;

public record ShipmentCorrectionHistoryPageVO(
    /** 当前物流信息的版本标识。 */
    int currentVersion,
    /** 当前对象的明细列表。 */
    List<ShipmentCorrectionHistoryVO> items
) {
}
