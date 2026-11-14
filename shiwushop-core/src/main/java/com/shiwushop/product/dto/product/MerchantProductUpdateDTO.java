package com.shiwushop.product.dto.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class MerchantProductUpdateDTO {
    /** 分类 ID。 */
    @NotNull
    @Positive
    private Long categoryId;

    /** 品牌 ID。 */
    @Positive
    private Long brandId;

    /** 名称。 */
    @NotBlank
    @Size(max = 100)
    private String name;

    /** 展示说明。 */
    private String description;

    /** 商品封面媒体 ID。 */
    @Positive
    private Long coverMediaId;

    /** 图片媒体 ID 列表。 */
    @Size(max = 20)
    private List<@NotNull @Positive Long> imageMediaIds;

    /** 旧版封面 URL 请求字段；仅用于拒绝旧契约输入，必须为空。 */
    @JsonProperty("coverUrl")
    @Null(message = "请使用coverMediaId设置封面")
    private String legacyCoverUrl;

    /** 旧版图片 URL 请求字段；仅用于拒绝旧契约输入，必须为空。 */
    @JsonProperty("imageUrls")
    @Null(message = "请使用imageMediaIds设置图片")
    private List<String> legacyImageUrls;
}
