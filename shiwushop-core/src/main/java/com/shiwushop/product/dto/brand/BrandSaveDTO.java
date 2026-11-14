package com.shiwushop.product.dto.brand;

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
public class BrandSaveDTO {

    /** 名称。 */
    @NotBlank(message = "品牌名称不能为空")
    @Size(max = 50, message = "品牌名称不能超过50个字符")
    private String name;

    /** 标识图片的媒体 ID。 */
    @Positive(message = "品牌标识媒体ID必须大于0")
    private Long logoMediaId;

    /** 旧版标识图片 URL 请求字段；仅用于拒绝旧契约输入，必须为空。 */
    @JsonProperty("logoUrl")
    @Null(message = "请使用logoMediaId设置品牌标识")
    private String legacyLogoUrl;

    /** 是否启用，1 是、0 否。 */
    @NotNull(message = "启用状态不能为空")
    @Min(value = 0, message = "启用状态只能是0或1")
    @Max(value = 1, message = "启用状态只能是0或1")
    private Integer enabled;
}
