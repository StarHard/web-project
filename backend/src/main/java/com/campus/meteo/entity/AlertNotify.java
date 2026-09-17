package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 告警通知明细实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("alert_notify")
public class AlertNotify extends BaseEntity {

    /** 告警记录ID */
    private Long alertId;

    /** 渠道：1Web 2短信 3邮件 4微信 */
    private Integer channel;

    /** 接收目标（userId/手机号/邮箱/openid） */
    private String target;

    /** 发送状态：0待发 1成功 2失败 */
    private Integer sendStatus;

    /** 发送时间 */
    private LocalDateTime sendTime;

    /** 失败原因 */
    private String failReason;
}
