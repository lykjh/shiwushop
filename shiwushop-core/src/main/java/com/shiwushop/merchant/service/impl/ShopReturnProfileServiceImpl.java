package com.shiwushop.merchant.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.merchant.dto.shop.ShopReturnProfileSaveDTO;
import com.shiwushop.merchant.entity.shop.ShopReturnProfile;
import com.shiwushop.merchant.mapper.shop.ShopReturnProfileMapper;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.merchant.vo.shop.ReturnDestinationSnapshot;
import com.shiwushop.merchant.service.ShopReturnProfileService;
import com.shiwushop.merchant.vo.shop.ReturnDestinationVO;
import com.shiwushop.merchant.vo.shop.ShopReturnProfileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShopReturnProfileServiceImpl implements ShopReturnProfileService {
    private final MerchantShopAccess access;
    private final ShopReturnProfileMapper mapper;
    private final PrivacyCipher cipher;

    /** 只读授权当前店主并返回其店铺的完整资料。 */
    @Override
    public ShopReturnProfileVO get(Long shopId, Long userId) {
        access.requireOwned(userId, shopId, "店铺不存在");
        ShopReturnProfile p = mapper.selectById(shopId);
        if (p == null) throw new BusinessException(409, "店铺退货资料未配置");
        return toVO(p);
    }

    /** 先锁店铺再锁配置，使整体覆盖与转店和退货快照串行。 */
    @Override
    @Transactional
    public ShopReturnProfileVO save(Long shopId, Long userId, ShopReturnProfileSaveDTO dto) {
        access.requireOwnedForUpdate(userId, shopId, "店铺不存在");
        ShopReturnProfile existing = mapper.selectForUpdate(shopId);
        ShopReturnProfile p = new ShopReturnProfile();
        p.setShopId(shopId);
        p.setKeyVersion(1);
        p.setContactNameCiphertext(cipher.encrypt(shopId, "shop-return:contact", dto.getContactName().strip()));
        p.setContactPhoneCiphertext(cipher.encrypt(shopId, "shop-return:contact-phone", dto.getContactPhone()));
        p.setConsigneeCiphertext(cipher.encrypt(shopId, "shop-return:consignee", dto.getConsignee().strip()));
        p.setPhoneCiphertext(cipher.encrypt(shopId, "shop-return:phone", dto.getPhone()));
        p.setProvince(dto.getProvince().strip());
        p.setCity(dto.getCity().strip());
        p.setDistrict(dto.getDistrict().strip());
        p.setDetailCiphertext(cipher.encrypt(shopId, "shop-return:detail", dto.getDetail().strip()));
        if (existing == null) mapper.insert(p); else mapper.updateById(p);
        BusinessEventLog.committed("merchant", "return-profile-save", "shop", shopId);
        return toVO(p);
    }

    /** 在调用方事务锁定配置并复制密文，缺配置时阻止退货审核。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReturnDestinationSnapshot snapshotForReturn(Long shopId) {
        ShopReturnProfile p = mapper.selectForUpdate(shopId);
        if (p == null) throw new BusinessException(409, "店铺退货资料未配置");
        return new ReturnDestinationSnapshot(shopId, p.getKeyVersion(), p.getConsigneeCiphertext(), p.getPhoneCiphertext(),
                p.getProvince(), p.getCity(), p.getDistrict(), p.getDetailCiphertext());
    }

    /** 按快照原店铺和密钥版本解密，仅返回退货所需信息。 */
    @Override
    public ReturnDestinationVO decryptReturnDestination(ReturnDestinationSnapshot s) {
        return new ReturnDestinationVO(cipher.decrypt(s.shopId(), "shop-return:consignee", s.keyVersion(), s.consigneeCiphertext()),
                cipher.decrypt(s.shopId(), "shop-return:phone", s.keyVersion(), s.phoneCiphertext()),
                s.province(), s.city(), s.district(), cipher.decrypt(s.shopId(), "shop-return:detail", s.keyVersion(), s.detailCiphertext()));
    }

    /** 解密仅供店主读取的经营与退货资料。 */
    private ShopReturnProfileVO toVO(ShopReturnProfile p) {
        Long id = p.getShopId();
        int version = p.getKeyVersion();
        return new ShopReturnProfileVO(cipher.decrypt(id, "shop-return:contact", version, p.getContactNameCiphertext()),
                cipher.decrypt(id, "shop-return:contact-phone", version, p.getContactPhoneCiphertext()),
                cipher.decrypt(id, "shop-return:consignee", version, p.getConsigneeCiphertext()),
                cipher.decrypt(id, "shop-return:phone", version, p.getPhoneCiphertext()),
                p.getProvince(), p.getCity(), p.getDistrict(), cipher.decrypt(id, "shop-return:detail", version, p.getDetailCiphertext()));
    }
}
