package com.shiwushop.merchant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.common.media.MediaAssetAccess;
import com.shiwushop.merchant.dto.application.MerchantApplicationDecisionDTO;
import com.shiwushop.merchant.dto.application.MerchantApplicationSaveDTO;
import com.shiwushop.merchant.entity.application.MerchantApplication;
import com.shiwushop.merchant.entity.application.MerchantApplicationDocument;
import com.shiwushop.merchant.entity.application.MerchantApplicationReview;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.merchant.mapper.application.MerchantApplicationDocumentMapper;
import com.shiwushop.merchant.mapper.application.MerchantApplicationMapper;
import com.shiwushop.merchant.mapper.application.MerchantApplicationReviewMapper;
import com.shiwushop.merchant.mapper.shop.ShopMapper;
import com.shiwushop.merchant.service.MerchantApplicationService;
import com.shiwushop.merchant.vo.application.MerchantApplicationVO;
import com.shiwushop.user.service.AccountAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MerchantApplicationServiceImpl implements MerchantApplicationService {
    /** 商家入驻提交、审核及证件有效期校验使用的业务时区。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    /** 入驻申请允许的证件类型：身份证、营业执照、食品经营许可证和健康证明。 */
    private static final Set<String> DOCUMENT_TYPES = Set.of("IDENTITY", "BUSINESS_LICENSE", "FOOD_LICENSE", "HEALTH_CERT");
    private final MerchantApplicationMapper applications;
    private final MerchantApplicationDocumentMapper documents;
    private final MerchantApplicationReviewMapper reviews;
    private final ShopMapper shops;
    private final AccountAccess accounts;
    private final MediaAssetAccess media;

    /** 创建一店一申请；资质图片须由申请人以私有用途上传。 */
    @Override
    @Transactional
    public MerchantApplicationVO create(Long applicantId, MerchantApplicationSaveDTO request) {
        accounts.requireActive(applicantId);
        validateBusinessType(request.businessType());
        validateDocuments(request.businessType(), request.documents());
        MerchantApplication application = new MerchantApplication();
        application.setApplicantUserId(applicantId);
        application.setShopName(request.shopName().strip());
        application.setBusinessType(request.businessType());
        application.setStatus("DRAFT");
        application.setVersion(1L);
        applications.insert(application);
        saveDocuments(application, request.documents());
        return view(application);
    }

    /** 草稿原位修改；驳回后开启新版本，旧审核版本与证照引用保持不变。 */
    @Override
    @Transactional
    public MerchantApplicationVO update(Long applicantId, Long id, MerchantApplicationSaveDTO request) {
        MerchantApplication application = ownedForUpdate(applicantId, id);
        if (!Set.of("DRAFT", "REJECTED", "APPROVED", "WITHDRAWN").contains(application.getStatus()))
            throw new BusinessException(409, "当前申请不可修改");
        validateBusinessType(request.businessType());
        validateDocuments(request.businessType(), request.documents());
        if (!"DRAFT".equals(application.getStatus())) {
            application.setVersion(application.getVersion() + 1);
            application.setStatus("DRAFT");
            application.setRejectReason(null);
        } else {
            documents.delete(new LambdaQueryWrapper<MerchantApplicationDocument>()
                    .eq(MerchantApplicationDocument::getApplicationId, id)
                    .eq(MerchantApplicationDocument::getApplicationVersion, application.getVersion()));
        }
        application.setShopName(request.shopName().strip());
        application.setBusinessType(request.businessType());
        applications.updateById(application);
        saveDocuments(application, request.documents());
        return view(application);
    }

    /** 提交冻结当前证照版本；同一待审版本重复提交只返回原状态。 */
    @Override
    @Transactional
    public MerchantApplicationVO submit(Long applicantId, Long id) {
        MerchantApplication application = ownedForUpdate(applicantId, id);
        if ("SUBMITTED".equals(application.getStatus()))
            return view(application);
        if (!"DRAFT".equals(application.getStatus()))
            throw new BusinessException(409, "当前申请不可提交");
        validateStoredDocuments(application);
        application.setStatus("SUBMITTED");
        application.setSubmittedAt(LocalDateTime.now(BUSINESS_ZONE));
        applications.updateById(application);
        BusinessEventLog.committed("merchant", "application_submitted", "application", id);
        return view(application);
    }

    /** 申请人撤回资质后即刻禁止新经营，原订单仍使用成交快照。 */
    @Override
    @Transactional
    public MerchantApplicationVO withdraw(Long applicantId, Long id) {
        MerchantApplication application = ownedForUpdate(applicantId, id);
        if ("WITHDRAWN".equals(application.getStatus()))
            return view(application);
        if (!Set.of("DRAFT", "REJECTED", "APPROVED").contains(application.getStatus()))
            throw new BusinessException(409, "待审核申请不能撤回");
        if (application.getShopId() != null) {
            Shop shop = shops.selectById(application.getShopId());
            shops.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Shop>()
                    .eq(Shop::getId, shop.getId())
                    .set(Shop::getQualificationRevoked, 1)
                    .set(Shop::getEnabled, 0));
        }
        application.setStatus("WITHDRAWN");
        applications.updateById(application);
        BusinessEventLog.committed("merchant", "application_withdrawn", "application", id);
        return view(application);
    }

    @Override
    public MerchantApplicationVO mine(Long applicantId, Long id) {
        MerchantApplication application = applications.selectById(id);
        if (application == null || !applicantId.equals(application.getApplicantUserId()))
            throw new BusinessException(404, "申请不存在");
        accounts.requireActive(applicantId);
        return view(application);
    }

    /** 审核在申请行锁下按版本只发生一次；批准时店铺、角色、审核行同事务提交。 */
    @Override
    @Transactional
    public MerchantApplicationVO decide(Long reviewerId, Long id, MerchantApplicationDecisionDTO request) {
        accounts.requireAdmin(reviewerId);
        MerchantApplication beforeLock = applications.selectById(id);
        if (beforeLock == null)
            throw new BusinessException(404, "申请不存在");
        Shop existingShop = beforeLock.getShopId() == null ? null : shops.selectForUpdate(beforeLock.getShopId());
        if (beforeLock.getShopId() != null && existingShop == null)
            throw new BusinessException(409, "店铺不存在");
        MerchantApplication application = applications.selectForUpdate(id);
        if (application == null)
            throw new BusinessException(404, "申请不存在");
        if (!java.util.Objects.equals(beforeLock.getShopId(), application.getShopId()))
            throw new BusinessException(409, "店铺归属已变化");
        if (!request.version().equals(application.getVersion()))
            throw new BusinessException(409, "申请版本已变化");
        String decision = request.approved() ? "APPROVED" : "REJECTED";
        if (Set.of("APPROVED", "REJECTED").contains(application.getStatus())) {
            MerchantApplicationReview prior = reviews.selectOne(new LambdaQueryWrapper<MerchantApplicationReview>()
                    .eq(MerchantApplicationReview::getApplicationId, id)
                    .eq(MerchantApplicationReview::getApplicationVersion, request.version()));
            if (prior != null && decision.equals(prior.getDecision()))
                return view(application);
            throw new BusinessException(409, "申请已审核");
        }
        if (!"SUBMITTED".equals(application.getStatus()))
            throw new BusinessException(409, "申请未提交");
        if (!request.approved() && (request.reason() == null || request.reason().isBlank()))
            throw new BusinessException(400, "驳回原因不能为空");
        if (request.approved()) {
            validateStoredDocuments(application);
            accounts.requireShopOwner(application.getApplicantUserId(), "申请人不可经营店铺");
            Shop shop = existingShop;
            if (shop == null) {
                shop = new Shop();
                shop.setOwnerUserId(application.getApplicantUserId());
                shop.setName(application.getShopName());
                shop.setEnabled(0);
                try {
                    shops.insert(shop);
                } catch (DuplicateKeyException e) {
                    throw new BusinessException(409, "店铺名称已存在", e);
                }
            } else if (!shop.getId().equals(application.getShopId())
                    || !application.getApplicantUserId().equals(shop.getOwnerUserId())) {
                throw new BusinessException(409, "店铺归属已变化");
            }
            LocalDate qualificationValidUntil = storedDocuments(application).stream()
                    .map(MerchantApplicationDocument::getValidUntil).filter(java.util.Objects::nonNull)
                    .min(LocalDate::compareTo).orElse(null);
            try {
                shops.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Shop>()
                        .eq(Shop::getId, shop.getId())
                        .set(Shop::getName, application.getShopName())
                        .set(Shop::getQualificationValidUntil, qualificationValidUntil)
                        .set(Shop::getQualificationRevoked, 0));
            } catch (DuplicateKeyException e) {
                throw new BusinessException(409, "店铺名称已存在", e);
            }
            accounts.promoteMerchant(application.getApplicantUserId());
            application.setShopId(shop.getId());
        }
        application.setStatus(decision);
        application.setRejectReason(request.approved() ? null : request.reason());
        application.setReviewedByUserId(reviewerId);
        application.setReviewedAt(LocalDateTime.now(BUSINESS_ZONE));
        applications.updateById(application);
        MerchantApplicationReview review = new MerchantApplicationReview();
        review.setApplicationId(id);
        review.setApplicationVersion(request.version());
        review.setDecision(decision);
        review.setReason(request.reason());
        review.setReviewedByUserId(reviewerId);
        review.setReviewedAt(application.getReviewedAt());
        reviews.insert(review);
        BusinessEventLog.committed("merchant", "application_" + decision.toLowerCase(), "application", id);
        return view(application);
    }

    @Override
    public List<MerchantApplicationVO> pending() {
        return applications.selectList(new LambdaQueryWrapper<MerchantApplication>()
                .eq(MerchantApplication::getStatus, "SUBMITTED")
                .orderByAsc(MerchantApplication::getId).last("LIMIT 100"))
                .stream().map(this::view).toList();
    }

    /** 管理员读取前先确认媒体确实属于该申请的当前提交版本。 */
    @Override
    public Long reviewMediaId(Long applicationId, String documentType) {
        MerchantApplication application = applications.selectById(applicationId);
        if (application == null || "DRAFT".equals(application.getStatus()))
            throw new BusinessException(404, "申请不存在");
        return storedDocuments(application).stream()
                .filter(d -> d.getDocumentType().equals(documentType))
                .map(MerchantApplicationDocument::getMediaId).findFirst()
                .orElseThrow(() -> new BusinessException(404, "资质图片不存在"));
    }

    private MerchantApplication ownedForUpdate(Long applicantId, Long id) {
        MerchantApplication beforeLock = applications.selectById(id);
        if (beforeLock == null || !applicantId.equals(beforeLock.getApplicantUserId()))
            throw new BusinessException(404, "申请不存在");
        Shop shop = beforeLock.getShopId() == null ? null : shops.selectForUpdate(beforeLock.getShopId());
        MerchantApplication application = applications.selectForUpdate(id);
        if (application == null || !applicantId.equals(application.getApplicantUserId()))
            throw new BusinessException(404, "申请不存在");
        if (!java.util.Objects.equals(beforeLock.getShopId(), application.getShopId()))
            throw new BusinessException(409, "店铺归属已变化");
        if (beforeLock.getShopId() != null && (shop == null || !shop.getId().equals(application.getShopId())
                || !applicantId.equals(shop.getOwnerUserId())))
            throw new BusinessException(404, "申请不存在");
        accounts.requireActive(applicantId);
        return application;
    }

    private void saveDocuments(MerchantApplication application, List<MerchantApplicationSaveDTO.Document> input) {
        input.stream().map(MerchantApplicationSaveDTO.Document::mediaId).distinct().sorted()
                .forEach(id -> media.requireReadyCredentialOwned(id, application.getApplicantUserId()));
        for (MerchantApplicationSaveDTO.Document item : input) {
            MerchantApplicationDocument document = new MerchantApplicationDocument();
            document.setApplicationId(application.getId());
            document.setApplicationVersion(application.getVersion());
            document.setDocumentType(item.documentType());
            document.setMediaId(item.mediaId());
            document.setValidUntil(item.validUntil());
            documents.insert(document);
        }
    }

    private void validateStoredDocuments(MerchantApplication application) {
        List<MerchantApplicationSaveDTO.Document> input = storedDocuments(application).stream()
                .map(d -> new MerchantApplicationSaveDTO.Document(d.getDocumentType(), d.getMediaId(), d.getValidUntil()))
                .toList();
        validateDocuments(application.getBusinessType(), input);
    }

    private List<MerchantApplicationDocument> storedDocuments(MerchantApplication application) {
        return documents.selectList(new LambdaQueryWrapper<MerchantApplicationDocument>()
                .eq(MerchantApplicationDocument::getApplicationId, application.getId())
                .eq(MerchantApplicationDocument::getApplicationVersion, application.getVersion())
                .orderByAsc(MerchantApplicationDocument::getId));
    }

    private void validateBusinessType(String businessType) {
        if (!Set.of("PERSONAL", "REGISTERED", "FOOD_HANDLING").contains(businessType))
            throw new BusinessException(400, "经营类型无效");
    }

    private void validateDocuments(String businessType, List<MerchantApplicationSaveDTO.Document> input) {
        Set<String> types = new HashSet<>();
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        for (MerchantApplicationSaveDTO.Document item : input) {
            if (!DOCUMENT_TYPES.contains(item.documentType()) || !types.add(item.documentType()))
                throw new BusinessException(400, "资质类型重复或无效");
            if (item.validUntil() != null && item.validUntil().isBefore(today))
                throw new BusinessException(409, "资质已过期");
        }
        Set<String> required = switch (businessType) {
            case "PERSONAL" -> Set.of("IDENTITY");
            case "REGISTERED" -> Set.of("BUSINESS_LICENSE");
            case "FOOD_HANDLING" -> Set.of("BUSINESS_LICENSE", "FOOD_LICENSE", "HEALTH_CERT");
            default -> throw new BusinessException(400, "经营类型无效");
        };
        if (!types.containsAll(required))
            throw new BusinessException(400, "缺少必需资质");
    }

    private MerchantApplicationVO view(MerchantApplication application) {
        return new MerchantApplicationVO(application.getId(), application.getShopId(), application.getShopName(),
                application.getBusinessType(), application.getStatus(), application.getVersion(),
                application.getRejectReason(), storedDocuments(application).stream()
                .map(d -> new MerchantApplicationVO.Document(d.getDocumentType(), d.getMediaId(), d.getValidUntil()))
                .toList());
    }
}
