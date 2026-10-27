package com.shiwushop.service.impl;

import com.shiwushop.dto.address.AddressSaveDTO;
import com.shiwushop.vo.address.AddressVO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountAccess;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.entity.address.Address;
import com.shiwushop.mapper.address.AddressMapper;
import com.shiwushop.service.AddressService;
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

    @Override
    public List<AddressVO> list(Long userId) {
        accountAccess.checkActive(userId);
        return addressMapper.selectList(new LambdaQueryWrapper<Address>()
                        .eq(Address::getUserId, userId)
                        .isNull(Address::getDeletedAt)
                        .orderByAsc(Address::getId))
                .stream().map(this::toVO).toList();
    }

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

    /** 缺少默认地址时优先选最近使用的地址，再按创建时间选；调用方已锁住用户行。 */
    private Long ensureDefault(Long userId) {
        Long defaultId = addressMapper.selectDefaultId(userId);
        if (defaultId != null) return defaultId;
        Long preferredId = addressMapper.selectPreferredId(userId);
        if (preferredId != null && addressMapper.setDefault(userId, preferredId) != 1) {
            throw new IllegalStateException("默认地址设置失败");
        }
        return preferredId;
    }

    private Address ownedAddress(Long userId, Long id) {
        accountAccess.checkActive(userId);
        Address address = addressMapper.selectOwnedActive(userId, id);
        if (address == null) {
            throw new BusinessException(404, "地址不存在");
        }
        return address;
    }

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
