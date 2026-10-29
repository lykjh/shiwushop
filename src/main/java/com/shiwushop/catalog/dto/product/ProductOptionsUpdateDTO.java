package com.shiwushop.catalog.dto.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ProductOptionsUpdateDTO {
    @NotEmpty
    private List<@NotNull @Valid ProductOptionSaveDTO> options;
}
