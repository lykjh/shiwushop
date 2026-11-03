package com.shiwushop.payment.dto;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;
public record ReconciliationResolveDTO(@NotNull @Positive Long evidenceId,
 @NotBlank @Pattern(regexp="CHANNEL_ONLY|LOCAL_ONLY|AMOUNT_MISMATCH|REFUND_STATUS_MISMATCH|NO_ACTION") String conclusionType,
 @NotBlank @CodePointLength(max=1000) String conclusion,
 @Pattern(regexp=".+") @CodePointLength(max=200) String manualHandlingReference) {
 public ReconciliationResolveDTO {
  if (conclusionType != null) conclusionType=conclusionType.strip();
  if (conclusion != null) conclusion=conclusion.strip();
  if (manualHandlingReference != null) manualHandlingReference=manualHandlingReference.strip();
 }
}
