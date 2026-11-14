package com.shiwushop.merchant.entity.application;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

@Data
@TableName("merchant_application_documents")
public class MerchantApplicationDocument {
    /** 入驻资质文件 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 入驻申请 ID。 */
    private Long applicationId;
    /** 本次提交的入驻申请版本。 */
    private Long applicationVersion;
    /** 入驻资质文件类型。 */
    private String documentType;
    /** 媒体资源 ID。 */
    private Long mediaId;
    /** 资质文件有效截止日期。 */
    private LocalDate validUntil;
}
