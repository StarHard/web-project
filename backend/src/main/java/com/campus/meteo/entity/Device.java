package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 观测设备实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("device")
public class Device extends BaseEntity {

    /** 所属站点ID */
    private Long stationId;

    /** 设备编码 */
    private String deviceCode;

    /** 类型：1风速风向 2雨量 3温湿压 4辐射 5蒸发 6能见度 7采集器 */
    private Integer deviceType;

    /** 型号 */
    private String model;

    /** 厂商 */
    private String manufacturer;

    /** 安装日期 */
    private LocalDate installDate;

    /** 状态：0停用 1正常 2故障 3检定中 */
    private Integer status;
}