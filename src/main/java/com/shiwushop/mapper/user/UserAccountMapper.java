package com.shiwushop.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.entity.user.UserAccount;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface UserAccountMapper extends BaseMapper<UserAccount> {
    @Update("""
            UPDATE user_accounts SET password_hash=#{hash}, password_changed_at=NOW(), auth_version=auth_version+1
            WHERE user_id=#{userId} AND auth_version=#{version} AND status='ACTIVE'
            """)
    int changePassword(@Param("userId") Long userId, @Param("version") int version,
                       @Param("hash") String hash);



    @Select("""
        SELECT user_id, username, password_hash, role, status, auth_version
        FROM user_accounts WHERE user_id = #{userId} FOR UPDATE
        """)
    UserAccount lockById(@Param("userId") Long userId);
}
