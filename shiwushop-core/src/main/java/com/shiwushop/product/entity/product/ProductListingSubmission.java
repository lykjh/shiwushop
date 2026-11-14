package com.shiwushop.product.entity.product;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("product_listing_submissions")
public class ProductListingSubmission {
    /** 当前记录 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 商品（SPU）ID。 */
    private Long productId;
    /** 商品内容修订号，用于上架审批和并发校验。 */
    private Long revision;
    /** 上架申请状态：PENDING 待审、APPROVED 通过、REJECTED 驳回。 */
    private String status;
    /** 提交上架申请的用户 ID。 */
    private Long submittedByUserId;
    /** 申请提交时间。 */
    private LocalDateTime submittedAt;
    /** 审核人用户 ID。 */
    private Long reviewedByUserId;
    /** 审核完成时间。 */
    private LocalDateTime reviewedAt;
    /** 本次业务操作的原因。 */
    private String reason;
}
