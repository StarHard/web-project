package com.campus.meteo.influx;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 标准化观测数据（采集链路统一数据模型）
 * 字段单位：temp(℃) humi(%) pres(hPa) wind_speed(m/s) wind_dir(°) rain(mm) rad(W/m²) vis(km) evap(mm)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObsData {

    /** 站点编码 */
    private String stationCode;

    /** 观测时刻 */
    private Instant ts;

    /** 要素值，键为要素名 */
    private Map<String, Double> elements;

    /** 质控标记 */
    private String qcFlag;

    /** 链路追踪消息ID（取自 MQTT 消息ID） */
    private String msgId;
}
