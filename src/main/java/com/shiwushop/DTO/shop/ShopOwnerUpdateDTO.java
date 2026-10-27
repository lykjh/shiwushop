package com.shiwushop.dto.shop;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class ShopOwnerUpdateDTO {

    @NotNull(message = "新经营者ID不能为空")
    @Positive(message = "新经营者ID必须为正数")
    private Long ownerUserId;
}
