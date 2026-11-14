package com.shiwushop.trade.dto.shipment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ShipmentPackageCreateDTO(
    /** 请求幂等键，用于识别同一业务请求。 */
    @NotBlank @Size(max = 64) String requestKey,
    /** 物流承运商名称。 */
    @NotBlank @Size(max = 80) String carrierName,
    /** 物流运单号。 */
    @NotBlank @Size(max = 100) String trackingNo,
    /** 当前对象的明细列表。 */
    @NotEmpty List<@Valid Item> items
) {
    public ShipmentPackageCreateDTO {
        if (requestKey != null)
            requestKey = requestKey.strip();
        if (carrierName != null)
            carrierName = carrierName.strip();
        if (trackingNo != null)
            trackingNo = trackingNo.strip();
        if (items != null)
            items = List.copyOf(items);
    }

    public record Item(
        /** 关联订单明细 ID。 */
        @Positive Long orderItemId,
        /** 商品件数。 */
        @Positive int quantity
    ) {
    }
}
