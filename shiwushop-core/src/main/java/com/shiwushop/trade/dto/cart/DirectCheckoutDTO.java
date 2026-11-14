package com.shiwushop.trade.dto.cart;

import jakarta.validation.constraints.Pattern;

import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.shiwushop.marketing.dto.ShopChoice;
import jakarta.validation.Valid;

import java.math.BigDecimal;

@Data
public class DirectCheckoutDTO {
    /** 商品规格（SKU）ID。 */
    @NotNull
    @Positive
    private Long skuId;
    /** 商品件数。 */
    @NotNull
    @Min(1)
    @Max(10000)
    private Integer quantity;
    /** 交易类型：NORMAL 普通、FLASH 秒杀、GROUP 拼团、BARGAIN 砍价、POINTS 积分兑换。 */
    @NotBlank
    @Pattern(regexp = "NORMAL|FLASH|GROUP|POINTS|BARGAIN")
    private String businessType = "NORMAL";
    /** 活动商品范围记录 ID，不是 SKU ID。 */
    @Positive
    private Long activitySkuId;
    /** 拼团团实例 ID。 */
    @Positive
    private Long groupId;
    /** 砍价实例 ID。 */
    @Positive
    private Long bargainId;
    /** 买家确认的应付积分最小单位数，供服务端重算核对；100 单位为 1 积分。 */
    @Min(0)
    private Long confirmedPointUnits;
    /** 收货地址 ID；下单时据此生成地址快照。 */
    @NotNull
    @Positive
    private Long addressId;
    /** 服务端报价指纹，下单时复核报价是否变化。 */
    @Pattern(regexp = "[a-f0-9]{64}")
    private String quoteHash;
    /** 各店铺明确选择的券、满减及满赠方案。 */
    @Size(max = 100)
    private List<@NotNull @Valid ShopChoice> shopChoices;
    /** 买家确认的现金应付总额，供服务端重算核对，单位元。 */
    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 16, fraction = 2)
    private BigDecimal confirmedTotal;
    /** 下单幂等键，防止同一请求重复生成订单。 */
    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "[\\x21-\\x7E]{1,64}")
    private String idempotencyKey;
}
