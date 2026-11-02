package com.shiwushop.user.service;

import com.shiwushop.user.dto.address.AddressSaveDTO;
import com.shiwushop.user.vo.address.AddressVO;


import java.util.List;
import com.shiwushop.user.entity.address.Address;
import java.time.LocalDateTime;

public interface AddressService {
    /** 结算读取本人有效地址；返回加密字段，禁止跨用户读取。 */
    Address forCheckout(Long userId, Long addressId);
    /** 与订单提交共用事务，记录地址曾被使用。 */
    void markUsed(Long userId, Long addressId, LocalDateTime usedAt);

    /** 创建当前用户的加密收货地址，并维护默认地址。 */
    AddressVO create(Long userId, AddressSaveDTO dto);
    /** 列出当前用户未删除的地址，以解密后的视图返回。 */
    List<AddressVO> list(Long userId);
    /** 修改本人地址，并维护同一用户只有一个默认地址的状态。 */
    AddressVO update(Long userId, Long id, AddressSaveDTO dto);
    /** 软删除本人地址；删除默认项后为剩余地址重新选择默认项。 */
    void delete(Long userId, Long id);
}
