package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 设备/站点运维记录实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("maintenance_record")
public class MaintenanceRecord extends BaseEntity {

    /** 站点ID */
    private Long stationId;

    /** 设备ID（为空表示站点级运维） */
    private Long deviceId;

    /** 类型：1检定 2维修 3更换 4巡检 */
    private Integer type;

    /** 内容描述 */
    private String content;

    /** 操作人 */
    private String operator;

    /** 运维日期 */
    private LocalDate maintDate;
}