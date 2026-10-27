package com.shiwushop.service;

import com.shiwushop.dto.address.AddressSaveDTO;
import com.shiwushop.vo.address.AddressVO;


import java.util.List;

public interface AddressService {
    AddressVO create(Long userId, AddressSaveDTO dto);
    List<AddressVO> list(Long userId);
    AddressVO update(Long userId, Long id, AddressSaveDTO dto);
    void delete(Long userId, Long id);
}
