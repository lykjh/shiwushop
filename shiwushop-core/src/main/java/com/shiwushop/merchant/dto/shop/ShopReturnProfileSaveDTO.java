package com.shiwushop.merchant.dto.shop;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShopReturnProfileSaveDTO {
    @NotBlank(message = "经营联系人不能为空")
    @Size(max = 20, message = "经营联系人不能超过20个字符")
    private String contactName;
    @NotBlank(message = "经营联系电话不能为空")
    @Pattern(regexp = "\\d{11}", message = "经营联系电话必须是11位数字")
    private String contactPhone;
    @NotBlank(message = "退货收件人不能为空")
    @Size(max = 20, message = "退货收件人不能超过20个字符")
    private String consignee;
    @NotBlank(message = "退货手机号不能为空")
    @Pattern(regexp = "\\d{11}", message = "退货手机号必须是11位数字")
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
}
