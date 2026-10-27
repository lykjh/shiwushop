package com.shiwushop.dto.address;

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

    @NotBlank(message = "收货人不能为空")
    @Size(max = 20, message = "收货人不能超过20个字符")
    private String consignee;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "\\d{11}", message = "手机号必须是11位数字")
    private String phone;

    @NotBlank(message = "省份不能为空")
    @Size(max = 64, message = "省份不能超过64个字符")
    private String province;

    @NotBlank(message = "城市不能为空")
    @Size(max = 64, message = "城市不能超过64个字符")
    private String city;

    @NotBlank(message = "区县不能为空")
    @Size(max = 64, message = "区县不能超过64个字符")
    private String district;

    @NotBlank(message = "详细地址不能为空")
    @Size(max = 255, message = "详细地址不能超过255个字符")
    private String detail;

    @Min(value = 0, message = "默认地址只能是0或1")
    @Max(value = 1, message = "默认地址只能是0或1")
    private Integer isDefault;

    @JsonProperty("address")
    @Null(message = "请分别填写省市区和详细地址")
    private String legacyAddress;
}
