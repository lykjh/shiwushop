package com.shiwushop.account.service.impl;

import com.shiwushop.account.dto.address.AddressSaveDTO;
import com.shiwushop.account.vo.address.AddressVO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.account.privacy.PrivacyCipher;
import com.shiwushop.account.entity.address.Address;
import com.shiwushop.account.mapper.address.AddressMapper;
import com.shiwushop.account.service.AddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.List;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {
    private final AddressMapper addressMapper;
    private final AccountAccess accountAccess;
    private final PrivacyCipher privacyCipher;
    private final Clock businessClock;

    /**
     * 创建收货地址。先锁定用户行串行化该用户的默认地址变更，敏感字段加密后写库；
     * 若这是用户的首个有效地址，则补设为默认地址。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AddressVO create(Long userId, AddressSaveDTO dto) {
        accountAccess.checkActive(userId);
        if (addressMapper.lockUser(userId) == null) {
            throw new BusinessException(401, "请重新登录");
        }
        if (Integer.valueOf(1).equals(dto.getIsDefault())) {
            addressMapper.clearDefault(userId);
        }
        Address address = new Address();
        address.setUserId(userId);
        copy(dto, address);
        addressMapper.insert(address);
        if (address.getId().equals(ensureDefault(userId))) {
            address.setIsDefault(1);
        }
        return toVO(address);
    }

    /** 只查询当前用户未删除的地址，并在返回前解密收货人、手机号和详细地址。 */
    @Override
    public List<AddressVO> list(Long userId) {
        accountAccess.checkActive(userId);
        return addressMapper.selectList(new LambdaQueryWrapper<Address>()
                        .eq(Address::getUserId, userId)
                        .isNull(Address::getDeletedAt)
                        .orderByAsc(Address::getId))
                .stream().map(this::toVO).toList();
    }

    /**
     * 在用户行锁保护下修改本人地址。调用方未指定默认状态时保留原值；
     * 指定为默认地址时先清除原默认地址，最后保证仍有一个默认地址。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AddressVO update(Long userId, Long id, AddressSaveDTO dto) {
        accountAccess.checkActive(userId);
        addressMapper.lockUser(userId);
        Address address = ownedAddress(userId, id);
        Integer wasDefault = address.getIsDefault();
        copy(dto, address);
        if (dto.getIsDefault() == null) {
            address.setIsDefault(wasDefault);
        }
        if (Integer.valueOf(1).equals(address.getIsDefault())) {
            addressMapper.clearDefault(userId);
        }
        addressMapper.updateById(address);
        if (address.getId().equals(ensureDefault(userId))) {
            address.setIsDefault(1);
        }
        return toVO(address);
    }

    /**
     * 软删除本人地址。写入删除时间并取消默认状态；删除默认地址后为剩余地址重新选择默认项。
     * 归属检查与写入条件都限制为当前用户，避免并发删除后误报成功。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long userId, Long id) {
        accountAccess.checkActive(userId);
        addressMapper.lockUser(userId);
        ownedAddress(userId, id);
        int changed = addressMapper.update(null, new LambdaUpdateWrapper<Address>()
                .eq(Address::getId, id)
                .eq(Address::getUserId, userId)
                .isNull(Address::getDeletedAt)
                .set(Address::getDeletedAt, LocalDateTime.now(businessClock))
                .set(Address::getIsDefault, 0));
        if (changed == 0) {
            throw new BusinessException(404, "地址不存在");
        }
        ensureDefault(userId);
    }

    /**
     * 返回现有默认地址 ID；没有默认地址时选择最近使用的有效地址并设为默认。
     * 调用方必须已锁住用户行，防止同一用户并发生成多个默认地址。
     */
    private Long ensureDefault(Long userId) {
        Long defaultId = addressMapper.selectDefaultId(userId);
        if (defaultId != null) return defaultId;
        Long preferredId = addressMapper.selectPreferredId(userId);
        if (preferredId != null && addressMapper.setDefault(userId, preferredId) != 1) {
            throw new IllegalStateException("默认地址设置失败");
        }
        return preferredId;
    }

    /** 确认地址属于当前有效用户且尚未删除；否则统一以 404 隐藏地址存在性。 */
    private Address ownedAddress(Long userId, Long id) {
        accountAccess.checkActive(userId);
        Address address = addressMapper.selectOwnedActive(userId, id);
        if (address == null) {
            throw new BusinessException(404, "地址不存在");
        }
        return address;
    }

    /** 将地址表单复制到实体，并按用户和字段名加密收货人、电话、详细地址。 */
    private void copy(AddressSaveDTO dto, Address address) {
        Long userId = address.getUserId();
        address.setConsigneeCiphertext(privacyCipher.encrypt(userId, "consignee", dto.getConsignee().strip()));
        address.setPhoneCiphertext(privacyCipher.encrypt(userId, "phone", dto.getPhone()));
        address.setProvince(dto.getProvince().strip());
        address.setCity(dto.getCity().strip());
        address.setDistrict(dto.getDistrict().strip());
        address.setDetailCiphertext(privacyCipher.encrypt(userId, "detail", dto.getDetail().strip()));
        address.setKeyVersion(1);
        address.setIsDefault(dto.getIsDefault() == null ? 0 : dto.getIsDefault());
    }

    /** 按实体记录的密钥版本解密敏感字段，组装仅供接口返回的地址视图。 */
    private AddressVO toVO(Address address) {
        Long userId = address.getUserId();
        int version = address.getKeyVersion();
        return new AddressVO(address.getId(),
                privacyCipher.decrypt(userId, "consignee", version, address.getConsigneeCiphertext()),
                privacyCipher.decrypt(userId, "phone", version, address.getPhoneCiphertext()),
                address.getProvince(), address.getCity(), address.getDistrict(),
                privacyCipher.decrypt(userId, "detail", version, address.getDetailCiphertext()),
                address.getIsDefault());
    }
}
