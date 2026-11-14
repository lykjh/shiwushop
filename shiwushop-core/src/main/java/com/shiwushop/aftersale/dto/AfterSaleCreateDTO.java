package com.shiwushop.aftersale.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record AfterSaleCreateDTO(
    /** 售后类型：REFUND_ONLY 仅退款、RETURN_REFUND 退货退款。 */
    @NotBlank @Pattern(regexp = "REFUND_ONLY|RETURN_REFUND") String type,
    /** 买家申请售后的原因。 */
    @NotBlank @Size(max = 500) String reason,
    /** 当前对象的明细列表。 */
    @NotEmpty @Size(max = 100) List<@NotNull @Valid Item> items
) {
    public AfterSaleCreateDTO {
        if (reason != null)
            reason = reason.strip();
    }

    public record Item(
        /** 关联订单明细 ID。 */
        @NotNull @Positive Long orderItemId,
        /** 商品件数。 */
        @NotNull @Positive Integer quantity,
        /** 关联发货包裹 ID。 */
        @Positive Long shipmentId,
        /** 退款件数的履约来源：UNSHIPPED 未发货、SHIPMENT 指定包裹。 */
        @Pattern(regexp = "UNSHIPPED|SHIPMENT") String sourceKind
    ) {
        public Item(Long orderItemId, Integer quantity) {
            this(orderItemId, quantity, null, null);
        }

        public Item(Long orderItemId, Integer quantity, Long shipmentId) {
            this(orderItemId, quantity, shipmentId, null);
        }
    }
}
