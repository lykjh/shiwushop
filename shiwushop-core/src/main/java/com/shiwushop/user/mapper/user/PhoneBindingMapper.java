package com.shiwushop.user.mapper.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import lombok.Data;

public interface PhoneBindingMapper {
    /** 按带密钥的摘要查找唯一绑定账号，不解密或扫描手机号。 */
    @Select("SELECT user_id FROM user_private WHERE phone_lookup_hash=#{hash}")
    Long findUserId(@Param("hash") String hash);

    /** 本人资料只读取解密所需的手机号密文和密钥版本。 */
    @Results({@Result(property = "ciphertext", column = "phone_ciphertext"),
            @Result(property = "keyVersion", column = "key_version")})
    @Select("SELECT phone_ciphertext, key_version FROM user_private WHERE user_id=#{userId}")
    PhonePrivate findPhone(@Param("userId") Long userId);

    @Data
    class PhonePrivate {
        private byte[] ciphertext;
        private int keyVersion;
    }

    /** 已锁定账号行后读取其绑定行，决定执行插入还是更新。 */
    @Select("SELECT user_id FROM user_private WHERE user_id=#{userId} FOR UPDATE")
    Long lockByUserId(@Param("userId") Long userId);

    @Insert("INSERT INTO user_private (user_id, phone_ciphertext, phone_lookup_hash, key_version) " +
            "VALUES (#{userId}, #{ciphertext}, #{hash}, 1)")
    int insert(@Param("userId") Long userId, @Param("ciphertext") byte[] ciphertext,
               @Param("hash") String hash);

    @Update("UPDATE user_private SET phone_ciphertext=#{ciphertext}, phone_lookup_hash=#{hash}, key_version=1 " +
            "WHERE user_id=#{userId}")
    int update(@Param("userId") Long userId, @Param("ciphertext") byte[] ciphertext,
               @Param("hash") String hash);
}
