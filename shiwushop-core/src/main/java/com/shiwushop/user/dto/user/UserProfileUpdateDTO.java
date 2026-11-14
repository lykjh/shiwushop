package com.shiwushop.user.dto.user;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.CodePointLength;

/** 本人资料请求；未提供邮箱时保留原值，显式 null 清除。 */
public class UserProfileUpdateDTO {
    /** 用户昵称。 */
    @NotNull @CodePointLength(min = 1, max = 40)
    private String nickname;
    /** 本人头像媒体 ID。 */
    @Positive
    private Long avatarMediaId;
    /** 普通联系邮箱；字段缺省保留原值，显式 null 清除。 */
    @Pattern(regexp = "(?i)(?=[^@]{1,64}@)[a-z0-9_%+\\-]+(?:\\.[a-z0-9_%+\\-]+)*@(qq|163|gmail)\\.com")
    private String email;
    /** 请求是否显式包含 email，区分缺省保留与 null 清除。 */
    private boolean emailSpecified;

    public UserProfileUpdateDTO() {
    }

    public UserProfileUpdateDTO(String nickname, Long avatarMediaId) {
        setNickname(nickname);
        this.avatarMediaId = avatarMediaId;
    }

    public String nickname() {
        return nickname;
    }

    public Long avatarMediaId() {
        return avatarMediaId;
    }

    public String email() {
        return email;
    }

    public boolean emailSpecified() {
        return emailSpecified;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname == null ? null : nickname.strip();
    }

    public void setAvatarMediaId(Long avatarMediaId) {
        this.avatarMediaId = avatarMediaId;
    }

    @JsonSetter(value = "email", nulls = Nulls.SET)
    public void setEmail(String email) {
        this.email = email;
        this.emailSpecified = true;
    }
}
