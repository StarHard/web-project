package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预报订正记录实体（forecast_order）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("forecast_order")
public class ForecastOrder extends BaseEntity {

    /** 站点ID */
    private Long stationId;

    /** 预报目标时段 */
    private LocalDateTime forecastTime;

    /** 要素 */
    private String element;

    /** 原始预报值 */
    private BigDecimal originValue;

    /** 订正值 */
    private BigDecimal revisedValue;

    /** 订正的预报员 */
    private Long orderUserId;

    /** 订正依据 */
    private String reason;
}