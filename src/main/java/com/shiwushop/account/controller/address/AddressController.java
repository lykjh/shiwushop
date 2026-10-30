package com.shiwushop.account.controller.address;

import com.shiwushop.account.dto.address.AddressSaveDTO;
import com.shiwushop.account.vo.address.AddressVO;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.account.service.AddressService;
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

    /** 接收地址表单，使用当前会话的用户 ID 创建地址并返回保存后的内容。 */
    @PostMapping
    public Result<AddressVO> create(@Valid @RequestBody AddressSaveDTO dto, HttpServletRequest request) {
        return Result.success(addressService.create(SessionUser.currentId(request), dto));
    }

    /** 查询当前会话用户的有效收货地址列表。 */
    @GetMapping
    public Result<List<AddressVO>> list(HttpServletRequest request) {
        return Result.success(addressService.list(SessionUser.currentId(request)));
    }

    /** 根据路径中的地址 ID 更新当前用户自己的地址；字段约束在进入服务前校验。 */
    @PutMapping("/{id}")
    public Result<AddressVO> update(@PathVariable Long id, @Valid @RequestBody AddressSaveDTO dto,
                                    HttpServletRequest request) {
        return Result.success(addressService.update(SessionUser.currentId(request), id, dto));
    }

    /** 软删除当前用户自己的地址；服务层负责归属和默认地址处理。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        addressService.delete(SessionUser.currentId(request), id);
        return Result.success();
    }

}
