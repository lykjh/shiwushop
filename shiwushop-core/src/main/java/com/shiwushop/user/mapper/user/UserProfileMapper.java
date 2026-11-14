package com.shiwushop.user.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.user.entity.user.UserProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import lombok.Data;

public interface UserProfileMapper extends BaseMapper<UserProfile> {
    @Insert("INSERT INTO users (nickname) VALUES (NULL)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertEmpty(UserProfile profile);

    @Update("UPDATE users SET nickname = #{nickname}, avatar_media_id = #{avatarMediaId} WHERE id = #{userId}")
    int replaceProfile(@Param("userId") Long userId, @Param("nickname") String nickname,
                       @Param("avatarMediaId") Long avatarMediaId);

    @Results({@Result(property = "ciphertext", column = "email_ciphertext"),
            @Result(property = "keyVersion", column = "key_version")})
    @Select("SELECT email_ciphertext, key_version FROM user_private WHERE user_id=#{userId}")
    EmailPrivate findEmail(@Param("userId") Long userId);

    @Data
    class EmailPrivate {
        /** 普通联系邮箱密文。 */
        private byte[] ciphertext;
        /** 隐私数据加密密钥版本。 */
        private int keyVersion;
    }

    /** 同一账号首次填写邮箱时创建私密行；已有手机号时仅替换邮箱密文。 */
    @Insert("INSERT INTO user_private (user_id, email_ciphertext, key_version) VALUES (#{userId}, #{ciphertext}, 1) " +
            "ON DUPLICATE KEY UPDATE email_ciphertext=#{ciphertext}")
    int replaceEmail(@Param("userId") Long userId, @Param("ciphertext") byte[] ciphertext);
}
