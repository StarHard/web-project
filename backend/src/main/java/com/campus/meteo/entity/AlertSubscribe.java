package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户告警订阅实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("alert_subscribe")
public class AlertSubscribe extends BaseEntity {

    /** 订阅用户 */
    private Long userId;

    /** 站点ID（0=全部站点） */
    private Long stationId;

    /** 告警类型（0=全部） */
    private Integer alertType;

    /** 接收渠道，逗号分隔：web,sms,email,wechat */
    private String channels;
}