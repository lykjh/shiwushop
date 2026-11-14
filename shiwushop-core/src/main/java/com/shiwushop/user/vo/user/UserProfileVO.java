package com.shiwushop.user.vo.user;

public record UserProfileVO(
    /** 用户昵称。 */
    String nickname,
    /** 头像媒体 ID。 */
    Long avatarMediaId,
    /** 用户普通联系邮箱，不作为登录凭证。 */
    String email
) {
}
