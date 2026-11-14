package com.shiwushop.aftersale.entity.timeout;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("after_sale_timeout_tasks")
public class AfterSaleTimeoutTask {
    /** 当前记录 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联售后单 ID。 */
    private Long caseId;
    /** 售后超时所处处理阶段。 */
    private String stage;
    /** 计算超时截止时间的基准时间。 */
    private LocalDateTime basisAt;
    /** 超时规则计算出的到期时间。 */
    private LocalDateTime dueAt;
    /** 权益或超时任务采用的规则版本。 */
    private Integer ruleVersion;
    /** 任务状态：SCHEDULED 待到期、OPEN 待认领、CLAIMED 已认领、CLOSED 已结案、STALE 已失效。 */
    private String status;
    /** 超时任务处理人用户 ID。 */
    private Long assigneeId;
    /** 人工处理所依据的凭据。 */
    private String evidence;
    /** 人工处理结论说明。 */
    private String conclusion;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 业务开启时间。 */
    private LocalDateTime openedAt;
    /** 任务认领时间。 */
    private LocalDateTime claimedAt;
    /** 业务关闭时间。 */
    private LocalDateTime closedAt;
}
