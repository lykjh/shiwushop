package com.shiwushop.dto.brand;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BrandCreateDTO {
    @NotBlank(message = "品牌名称不能为空")
    @Size(max = 50, message = "品牌名称不能超过50个字符")
    private String name;

    @Positive(message = "品牌标识媒体ID必须大于0")
    private Long logoMediaId;

    @JsonProperty("logoUrl")
    @Null(message = "请使用logoMediaId设置品牌标识")
    private String legacyLogoUrl;

    @NotNull(message = "启用状态不能为空")
    @Min(value = 0, message = "启用状态只能是0或1")
    @Max(value = 1, message = "启用状态只能是0或1")
    private Integer enabled;
}
