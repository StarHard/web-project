package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 气象站点实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("station")
public class Station extends BaseEntity {

    /** 站点编码（MQTT 上报标识） */
    private String stationCode;

    /** 站点名称 */
    private String name;

    private String province;
    private String city;
    private String district;

    /** 经度（GCJ-02） */
    private BigDecimal longitude;

    /** 纬度（GCJ-02） */
    private BigDecimal latitude;

    /** 海拔（米） */
    private BigDecimal altitude;

    /** 类型：1校园 2农业 3区域 */
    private Integer stationType;

    /** 状态：0停用 1正常 2维护中 */
    private Integer status;

    /** 在线：0离线 1在线（采集Agent维护） */
    private Integer onlineFlag;

    /** 最后上报时间 */
    private LocalDateTime lastReportTime;

    /** 投运日期 */
    private LocalDate commissionDate;
}
