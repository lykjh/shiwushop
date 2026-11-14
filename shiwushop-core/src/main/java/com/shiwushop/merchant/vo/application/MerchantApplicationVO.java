package com.shiwushop.merchant.vo.application;

import java.time.LocalDate;
import java.util.List;

/** 只供申请人和有权管理员查看；公开店铺接口从不返回证照 ID。 */
public record MerchantApplicationVO(
    /** 入驻申请 ID。 */
    Long id,
    /** 所属店铺 ID。 */
    Long shopId,
    /** 店铺名称。 */
    String shopName,
    /** 入驻经营类型：PERSONAL 个人、REGISTERED 注册经营主体、FOOD_HANDLING 食品经营。 */
    String businessType,
    /** 入驻申请状态：DRAFT 草稿、SUBMITTED 已提交、APPROVED 通过、REJECTED 驳回、WITHDRAWN 撤回。 */
    String status,
    /** 并发更新版本号。 */
    Long version,
    /** 驳回原因。 */
    String rejectReason,
    /** 本次入驻申请的资质文件列表。 */
    List<Document> documents
) {
    public record Document(
        /** 入驻资质文件类型。 */
        String documentType,
        /** 媒体资源 ID。 */
        Long mediaId,
        /** 资质文件有效截止日期。 */
        LocalDate validUntil
    ) {
    }
}
