package com.shiwushop.dto.shop;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShopProfileUpdateDTO {

    @NotBlank(message = "店铺名称不能为空")
    @Size(max = 100, message = "店铺名称不能超过100个字符")
    private String name;

    @Positive(message = "店铺标识媒体ID必须大于0")
    private Long logoMediaId;

    @JsonProperty("logoUrl")
    @Null(message = "请使用logoMediaId设置店铺标识")
    private String legacyLogoUrl;

    @Size(max = 1000, message = "店铺简介不能超过1000个字符")
    private String description;
}
