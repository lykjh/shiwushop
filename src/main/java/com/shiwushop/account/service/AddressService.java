package com.shiwushop.account.service;

import com.shiwushop.account.dto.address.AddressSaveDTO;
import com.shiwushop.account.vo.address.AddressVO;


import java.util.List;

public interface AddressService {
    AddressVO create(Long userId, AddressSaveDTO dto);
    List<AddressVO> list(Long userId);
    AddressVO update(Long userId, Long id, AddressSaveDTO dto);
    void delete(Long userId, Long id);
}
