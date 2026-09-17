package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 告警规则实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("alert_rule")
public class AlertRule extends BaseEntity {

    /** 站点ID（0表示全局） */
    private Long stationId;

    /** 类型：1暴雨 2大风 3高温 4寒潮 5冰雹 */
    private Integer alertType;

    /** 判定要素 */
    private String element;

    /** 条件：1大于 2小于 3持续N分钟超限（condition 为 SQL 保留字，须转义） */
    @TableField("`condition`")
    private Integer condition;

    /** 阈值 */
    private BigDecimal threshold;

    /** 持续时长（分钟，condition=3 时） */
    private Integer durationMin;

    /** 等级：1蓝 2黄 3橙 4红 */
    private Integer level;

    /** 升级等级 */
    private Integer upgradeLevel;

    /** 推送渠道集合，逗号分隔：web,sms,email,wechat */
    private String channels;

    /** 状态：0停用 1启用 */
    private Integer status;
}
