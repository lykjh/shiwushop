package com.shiwushop.trade.dto.cart;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.shiwushop.marketing.dto.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

public record QuoteRequest(
    /** 报价来源：CART 购物车、DIRECT 直接购买。 */
    @NotNull @Pattern(regexp = "CART|DIRECT") String source,
    /** 参与结算的购物车明细 ID 列表。 */
    @Size(min = 1, max = 100) @JsonDeserialize(contentUsing = JsonLongValue.class) List<@NotNull @Positive Long> cartItemIds,
    /** 商品规格（SKU）ID。 */
    @Positive @JsonDeserialize(using = JsonLongValue.class) Long skuId,
    /** 商品件数。 */
    @Min(1) @Max(10000) @JsonDeserialize(using = JsonIntegerValue.class) Integer quantity,
    /** 收货地址 ID；下单时据此生成地址快照。 */
    @NotNull @Positive @JsonDeserialize(using = JsonLongValue.class) Long addressId,
    /** 交易类型：NORMAL 普通、FLASH 秒杀、GROUP 拼团、BARGAIN 砍价、POINTS 积分兑换。 */
    @Pattern(regexp = "NORMAL|FLASH|GROUP|BARGAIN|POINTS") String businessType,
    /** 活动商品范围记录 ID，不是 SKU ID。 */
    @Positive @JsonDeserialize(using = JsonLongValue.class) Long activitySkuId,
    /** 拼团团实例 ID。 */
    @Positive @JsonDeserialize(using = JsonLongValue.class) Long groupId,
    /** 砍价实例 ID。 */
    @Positive @JsonDeserialize(using = JsonLongValue.class) Long bargainId,
    /** 各店铺明确选择的券、满减及满赠方案。 */
    @Size(max = 100) List<@NotNull @Valid ShopChoice> shopChoices
) {
    public QuoteRequest {
        if (businessType == null)
            businessType = "NORMAL";
        if (shopChoices == null)
            shopChoices = List.of();
    }

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("不接受报价字段：" + field);
    }
}
