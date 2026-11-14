package com.shiwushop.user.dto.address;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AddressSaveDTO {

    /** 收货人姓名。 */
    @NotBlank(message = "收货人不能为空")
    @Size(max = 20, message = "收货人不能超过20个字符")
    private String consignee;

    /** 手机号。 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "\\d{11}", message = "手机号必须是11位数字")
    private String phone;

    /** 地址所在省份。 */
    @NotBlank(message = "省份不能为空")
    @Size(max = 64, message = "省份不能超过64个字符")
    private String province;

    /** 地址所在城市。 */
    @NotBlank(message = "城市不能为空")
    @Size(max = 64, message = "城市不能超过64个字符")
    private String city;

    /** 地址所在区县。 */
    @NotBlank(message = "区县不能为空")
    @Size(max = 64, message = "区县不能超过64个字符")
    private String district;

    /** 详细地址。 */
    @NotBlank(message = "详细地址不能为空")
    @Size(max = 255, message = "详细地址不能超过255个字符")
    private String detail;

    /** 是否为默认收货地址，1 是、0 否。 */
    @Min(value = 0, message = "默认地址只能是0或1")
    @Max(value = 1, message = "默认地址只能是0或1")
    private Integer isDefault;

    /** 旧版完整地址请求字段；仅用于拒绝旧契约输入，必须为空。 */
    @JsonProperty("address")
    @Null(message = "请分别填写省市区和详细地址")
    private String legacyAddress;
}
