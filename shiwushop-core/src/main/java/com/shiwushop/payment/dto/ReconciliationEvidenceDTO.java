package com.shiwushop.payment.dto;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;
public record ReconciliationEvidenceDTO(@NotBlank @CodePointLength(max=2000) String billEvidence,
 @NotBlank @CodePointLength(max=2000) String localEvidence,
 @NotBlank @CodePointLength(max=2000) String channelQueryEvidence) {
 public ReconciliationEvidenceDTO {
  if (billEvidence != null) billEvidence=billEvidence.strip();
  if (localEvidence != null) localEvidence=localEvidence.strip();
  if (channelQueryEvidence != null) channelQueryEvidence=channelQueryEvidence.strip();
 }
}
