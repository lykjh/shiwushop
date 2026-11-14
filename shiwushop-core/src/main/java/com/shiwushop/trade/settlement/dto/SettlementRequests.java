package com.shiwushop.trade.settlement.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class SettlementRequests {
    private SettlementRequests() {
    }

    public record Selection(
        /** 关联订单 ID。 */
        @NotNull @Positive Long orderId,
        /** 本次结算收款归属的核对凭据。 */
        @NotBlank @Size(max = 255) String ownershipEvidence
    ) {
    }

    public record Create(
        /** 所属店铺 ID。 */
        @NotNull @Positive Long shopId,
        /** 收款用户 ID。 */
        @NotNull @Positive Long payeeUserId,
        /** 请求幂等键，用于识别同一业务请求。 */
        @NotBlank @Pattern(regexp = "^[!-~]{1,64}$") String requestKey,
        /** 本次处理的订单列表。 */
        @NotEmpty @Size(max = 50) List<@Valid Selection> orders
    ) {
    }

    public record Key(
        /** 请求幂等键，用于识别同一业务请求。 */
        @NotBlank @Pattern(regexp = "^[!-~]{1,64}$") String requestKey
    ) {
    }

    public record Payment(
        /** 请求幂等键，用于识别同一业务请求。 */
        @NotBlank @Pattern(regexp = "^[!-~]{1,64}$") String requestKey,
        /** 本次登记的实际付款金额，单位元。 */
        @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal actualAmount,
        /** 支付或结算完成时间。 */
        @NotNull LocalDateTime paidAt,
        /** 实际付款方式。 */
        @NotBlank @Size(max = 32) String method,
        /** 外部付款流水或业务编号。 */
        @Size(max = 128) String externalReference,
        /** 实际付款凭据引用。 */
        @Size(max = 255) String proofReference,
        /** 收款用户 ID。 */
        @NotNull @Positive Long payeeUserId
    ) {
    }

    public record Void(
        /** 请求幂等键，用于识别同一业务请求。 */
        @NotBlank @Pattern(regexp = "^[!-~]{1,64}$") String requestKey,
        /** 本次业务操作的原因。 */
        @NotBlank @Size(max = 500) String reason,
        /** 证明结算尚未实际付款的凭据。 */
        @NotBlank @Size(max = 255) String noPaymentEvidence
    ) {
    }

    public record Correction(
        /** 请求幂等键，用于识别同一业务请求。 */
        @NotBlank @Pattern(regexp = "^[!-~]{1,64}$") String requestKey,
        /** 更正类别：AMOUNT 金额、PAYEE 收款人、EVIDENCE 付款凭据。 */
        @NotBlank @Pattern(regexp = "AMOUNT|PAYEE|EVIDENCE") String kind,
        /** 更正申请的金额增减值，单位元。 */
        @Digits(integer = 16, fraction = 2) BigDecimal amountDelta,
        /** 更正申请中拟改为的收款用户 ID。 */
        @Positive Long proposedPayeeUserId,
        /** 更正后的付款方式。 */
        @Size(max = 32) String replacementMethod,
        /** 更正后的外部付款流水编号。 */
        @Size(max = 128) String replacementExternalReference,
        /** 更正后的付款凭据引用。 */
        @Size(max = 255) String replacementProofReference,
        /** 本次业务操作的原因。 */
        @NotBlank @Size(max = 500) String reason,
        /** 操作凭据引用。 */
        @NotBlank @Size(max = 255) String evidenceReference
    ) {
    }

    public record Review(
        /** 请求幂等键，用于识别同一业务请求。 */
        @NotBlank @Pattern(regexp = "^[!-~]{1,64}$") String requestKey,
        /** 操作凭据引用。 */
        @NotBlank @Size(max = 255) String evidenceReference
    ) {
    }
}
