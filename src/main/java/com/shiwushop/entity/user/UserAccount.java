package com.shiwushop.entity.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shiwushop.entity.status.AccountRole;
import com.shiwushop.entity.status.AccountStatus;
import lombok.Data;

@Data
@TableName("user_accounts")
public class UserAccount {
    @TableId(type = IdType.INPUT)
    private Long userId;
    private String username;
    private String passwordHash;
    private AccountRole role;
    private AccountStatus status;
    private Integer authVersion;
}
