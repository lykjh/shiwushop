package com.shiwushop.aftersale.entity.timeout;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("after_sale_timeout_tasks")
public class AfterSaleTimeoutTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private String stage;
    private LocalDateTime basisAt;
    private LocalDateTime dueAt;
    private Integer ruleVersion;
    private String status;
    private Long assigneeId;
    private String evidence;
    private String conclusion;
    private LocalDateTime createdAt;
    private LocalDateTime openedAt;
    private LocalDateTime claimedAt;
    private LocalDateTime closedAt;
}
