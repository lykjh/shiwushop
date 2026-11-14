package com.shiwushop.user.entity.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("users")
public class UserProfile {
    /** 用户 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 用户昵称。 */
    private String nickname;
    /** 头像媒体 ID。 */
    private Long avatarMediaId;
}
