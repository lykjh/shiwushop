package com.shiwushop.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;

import java.time.LocalDate;

public record PaymentBillDownloadDTO(
    /** 支付渠道账单所属日期。 */
    @NotNull @Past LocalDate billDate
) {
}
