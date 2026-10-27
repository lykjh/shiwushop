package com.shiwushop.dto.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ProductOptionSaveDTO {
    @NotBlank
    @Size(max = 30)
    private String name;

    @NotEmpty
    private List<@NotBlank @Size(max = 50) String> values;
}
