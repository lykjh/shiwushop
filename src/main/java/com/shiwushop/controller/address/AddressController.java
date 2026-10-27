package com.shiwushop.controller.address;

import com.shiwushop.dto.address.AddressSaveDTO;
import com.shiwushop.vo.address.AddressVO;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.service.AddressService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/addresses")
@RequiredArgsConstructor
public class AddressController {
    private final AddressService addressService;

    @PostMapping
    public Result<AddressVO> create(@Valid @RequestBody AddressSaveDTO dto, HttpServletRequest request) {
        return Result.success(addressService.create(SessionUser.currentId(request), dto));
    }

    @GetMapping
    public Result<List<AddressVO>> list(HttpServletRequest request) {
        return Result.success(addressService.list(SessionUser.currentId(request)));
    }

    @PutMapping("/{id}")
    public Result<AddressVO> update(@PathVariable Long id, @Valid @RequestBody AddressSaveDTO dto,
                                    HttpServletRequest request) {
        return Result.success(addressService.update(SessionUser.currentId(request), id, dto));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        addressService.delete(SessionUser.currentId(request), id);
        return Result.success();
    }

}
