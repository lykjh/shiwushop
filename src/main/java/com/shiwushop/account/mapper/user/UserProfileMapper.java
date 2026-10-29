package com.shiwushop.account.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.account.entity.user.UserProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

public interface UserProfileMapper extends BaseMapper<UserProfile> {
    @Insert("INSERT INTO users (nickname) VALUES (NULL)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertEmpty(UserProfile profile);
}
