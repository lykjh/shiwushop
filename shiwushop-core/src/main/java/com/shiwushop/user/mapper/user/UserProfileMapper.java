package com.shiwushop.user.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.user.entity.user.UserProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UserProfileMapper extends BaseMapper<UserProfile> {
    @Insert("INSERT INTO users (nickname) VALUES (NULL)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertEmpty(UserProfile profile);

    @Update("UPDATE users SET nickname = #{nickname}, avatar_media_id = #{avatarMediaId} WHERE id = #{userId}")
    int replaceProfile(@Param("userId") Long userId, @Param("nickname") String nickname,
                       @Param("avatarMediaId") Long avatarMediaId);
}
