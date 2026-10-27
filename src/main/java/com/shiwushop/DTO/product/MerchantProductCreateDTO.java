package com.shiwushop.dto.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class MerchantProductCreateDTO {
    @NotNull
    @Positive
    private Long shopId;

    @NotNull
    @Positive
    private Long categoryId;

    @Positive
    private Long brandId;

    @NotBlank
    @Size(max = 100)
    private String name;

    private String description;

    @Positive
    private Long coverMediaId;

    @Size(max = 20)
    private List<@NotNull @Positive Long> imageMediaIds;

    @JsonProperty("coverUrl")
    @Null(message = "请使用coverMediaId设置封面")
    private String legacyCoverUrl;

    @JsonProperty("imageUrls")
    @Null(message = "请使用imageMediaIds设置图片")
    private List<String> legacyImageUrls;
}
