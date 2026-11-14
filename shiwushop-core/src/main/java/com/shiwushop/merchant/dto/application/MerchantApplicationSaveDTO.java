package com.shiwushop.merchant.dto.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record MerchantApplicationSaveDTO(
    /** 店铺名称。 */
    @NotBlank @Size(max = 100) String shopName,
    /** 入驻经营类型：PERSONAL 个人、REGISTERED 注册经营主体、FOOD_HANDLING 食品经营。 */
    @NotBlank String businessType,
    /** 本次入驻申请的资质文件列表。 */
    @NotEmpty List<@Valid Document> documents
) {
    public record Document(
        /** 入驻资质文件类型。 */
        @NotBlank String documentType,
        /** 媒体资源 ID。 */
        @NotNull @Positive Long mediaId,
        /** 资质文件有效截止日期。 */
        LocalDate validUntil
    ) {
    }
}
