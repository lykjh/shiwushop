package com.shiwushop.catalog.dto.shop;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ShopStatusUpdateDTO {

    @NotNull(message = "营业状态不能为空")
    @Min(value = 0, message = "营业状态只能是0或1")
    @Max(value = 1, message = "营业状态只能是0或1")
    private Integer enabled;
}
