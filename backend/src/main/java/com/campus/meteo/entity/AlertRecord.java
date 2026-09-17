package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 告警记录实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("alert_record")
public class AlertRecord extends BaseEntity {

    /** 触发的规则ID */
    private Long ruleId;

    /** 站点ID */
    private Long stationId;

    /** 触发时等级：1蓝 2黄 3橙 4红 */
    private Integer level;

    /** 告警时间 */
    private LocalDateTime alertTime;

    /** 触发值 */
    private BigDecimal obsValue;

    /** 告警描述 */
    private String content;

    /** 状态：0进行中 1已解除 2已升级 */
    private Integer status;

    /** 解除时间 */
    private LocalDateTime relieveTime;
}
