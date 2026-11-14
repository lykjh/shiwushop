package com.shiwushop.trade.dto.cart;

import jakarta.validation.constraints.Pattern;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.shiwushop.marketing.dto.ShopChoice;
import jakarta.validation.Valid;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CheckoutDTO {
    /** 一次结算允许提交的购物车明细数上限。 */
    public static final int MAX_CART_ITEMS = 100;

    /** 参与结算的购物车明细 ID 列表。 */
    @NotEmpty
    @Size(max = MAX_CART_ITEMS)
    private List<@NotNull @Positive Long> cartItemIds;
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
    private String idempotencyKey;
}
