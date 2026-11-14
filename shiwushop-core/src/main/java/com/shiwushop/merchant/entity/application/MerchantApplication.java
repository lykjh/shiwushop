package com.shiwushop.merchant.entity.application;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant_applications")
public class MerchantApplication {
    /** 入驻申请 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 入驻申请人用户 ID。 */
    private Long applicantUserId;
    /** 所属店铺 ID。 */
    private Long shopId;
    /** 店铺名称。 */
    private String shopName;
    /** 入驻经营类型：PERSONAL 个人、REGISTERED 注册经营主体、FOOD_HANDLING 食品经营。 */
    private String businessType;
    /** 入驻申请状态：DRAFT 草稿、SUBMITTED 已提交、APPROVED 通过、REJECTED 驳回、WITHDRAWN 撤回。 */
    private String status;
    /** 并发更新版本号。 */
    private Long version;
    /** 驳回原因。 */
    private String rejectReason;
    /** 申请提交时间。 */
    private LocalDateTime submittedAt;
    /** 审核人用户 ID。 */
    private Long reviewedByUserId;
    /** 审核完成时间。 */
    private LocalDateTime reviewedAt;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 记录最近更新时间。 */
    private LocalDateTime updatedAt;
}
