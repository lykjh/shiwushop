package com.shiwushop.account.service;

import com.shiwushop.account.dto.address.AddressSaveDTO;
import com.shiwushop.account.vo.address.AddressVO;


import java.util.List;

public interface AddressService {
    /** 创建当前用户的加密收货地址，并维护默认地址。 */
    AddressVO create(Long userId, AddressSaveDTO dto);
    /** 列出当前用户未删除的地址，以解密后的视图返回。 */
    List<AddressVO> list(Long userId);
    /** 修改本人地址，并维护同一用户只有一个默认地址的状态。 */
    AddressVO update(Long userId, Long id, AddressSaveDTO dto);
    /** 软删除本人地址；删除默认项后为剩余地址重新选择默认项。 */
    void delete(Long userId, Long id);
}
