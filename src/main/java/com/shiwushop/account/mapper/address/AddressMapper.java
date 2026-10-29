package com.shiwushop.account.mapper.address;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.account.entity.address.Address;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AddressMapper extends BaseMapper<Address> {
    default Address selectOwnedActive(Long userId, Long addressId) {
        return selectOne(new LambdaQueryWrapper<Address>()
                .eq(Address::getId, addressId)
                .eq(Address::getUserId, userId)
                .isNull(Address::getDeletedAt));
    }

    @Select("SELECT id FROM users WHERE id = #{userId} FOR UPDATE")
    Long lockUser(@Param("userId") Long userId);

    @Update("UPDATE addresses SET is_default = 0 WHERE user_id = #{userId} AND deleted_at IS NULL AND is_default = 1")
    int clearDefault(@Param("userId") Long userId);

    @Select("SELECT id FROM addresses WHERE user_id = #{userId} AND deleted_at IS NULL AND is_default = 1 LIMIT 1")
    Long selectDefaultId(@Param("userId") Long userId);

    @Select("SELECT id FROM addresses WHERE user_id = #{userId} AND deleted_at IS NULL "
            + "ORDER BY last_used_at DESC, created_at DESC, id DESC LIMIT 1")
    Long selectPreferredId(@Param("userId") Long userId);

    @Update("UPDATE addresses SET is_default = 1 WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL")
    int setDefault(@Param("userId") Long userId, @Param("id") Long id);

    @Update("UPDATE addresses SET last_used_at = #{usedAt} WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL")
    int markUsed(@Param("userId") Long userId, @Param("id") Long id, @Param("usedAt") LocalDateTime usedAt);
}
