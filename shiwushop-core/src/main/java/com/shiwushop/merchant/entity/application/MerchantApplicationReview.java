package com.shiwushop.merchant.entity.application;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant_application_reviews")
public class MerchantApplicationReview {
    /** 入驻审核记录 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 入驻申请 ID。 */
    private Long applicationId;
    /** 本次提交的入驻申请版本。 */
    private Long applicationVersion;
    /** 审核决定。 */
    private String decision;
    /** 本次业务操作的原因。 */
    private String reason;
    /** 审核人用户 ID。 */
    private Long reviewedByUserId;
    /** 审核完成时间。 */
    private LocalDateTime reviewedAt;
}
