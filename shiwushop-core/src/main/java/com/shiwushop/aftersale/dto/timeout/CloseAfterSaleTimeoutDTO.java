package com.shiwushop.aftersale.dto.timeout;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CloseAfterSaleTimeoutDTO(@NotBlank @Size(max = 1000) String evidence,
                                       @NotBlank @Size(max = 1000) String conclusion) {}
