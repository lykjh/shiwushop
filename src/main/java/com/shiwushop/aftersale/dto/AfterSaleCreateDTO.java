package com.shiwushop.aftersale.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record AfterSaleCreateDTO(@NotBlank @Pattern(regexp = "REFUND_ONLY|RETURN_REFUND") String type,
                                 @NotBlank @Size(max = 500) String reason,
                                 @NotEmpty @Size(max = 100) List<@NotNull @Valid Item> items) {
    public AfterSaleCreateDTO { if (reason != null) reason = reason.strip(); }
    public record Item(@NotNull @Positive Long orderItemId, @NotNull @Positive Integer quantity) {}
}
