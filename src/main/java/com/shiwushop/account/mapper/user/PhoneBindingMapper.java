package com.shiwushop.account.mapper.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PhoneBindingMapper {
    /** 按带密钥的摘要查找唯一绑定账号，不解密或扫描手机号。 */
    @Select("SELECT user_id FROM user_private WHERE phone_lookup_hash=#{hash}")
    Long findUserId(@Param("hash") String hash);

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
