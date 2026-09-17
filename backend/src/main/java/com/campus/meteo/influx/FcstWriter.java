package com.campus.meteo.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * 预报产品写入 InfluxDB 的统一入口（measurement: fcst）
 * tags: station_code, model（nwp/ml/stat/manual）, issue（发布时刻epoch秒）
 * fields: temp, humi, pres, wind_speed, wind_dir, rain, pop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FcstWriter {

    public static final String MEASUREMENT_FCST = "fcst";
    public static final String TAG_STATION = "station_code";
    public static final String TAG_MODEL = "model";
    public static final String TAG_ISSUE = "issue";

    private final InfluxDBClient client;
    private final InfluxProperties properties;

    /**
     * 写入一次发布的多时效预报序列
     *
     * @param stationCode 站点编码
     * @param model       模型标识（stat 统计降尺度 / ml 机器学习 / manual 人工订正）
     * @param issueTime   发布时刻
     * @param series      预报目标时刻 → 各要素值
     */
    public void writeFcst(String stationCode, String model, Instant issueTime,
                          Map<Instant, Map<String, Double>> series) {
        String issue = String.valueOf(issueTime.getEpochSecond());
        for (Map.Entry<Instant, Map<String, Double>> entry : series.entrySet()) {
            Point point = Point.measurement(MEASUREMENT_FCST)
                    .addTag(TAG_STATION, stationCode)
                    .addTag(TAG_MODEL, model)
                    .addTag(TAG_ISSUE, issue)
                    .time(entry.getKey(), WritePrecision.S);
            entry.getValue().forEach((k, v) -> {
                if (v != null && !v.isNaN()) {
                    point.addField(k, v);
                }
            });
            client.getWriteApiBlocking().writePoint(properties.getBucket(), properties.getOrg(), point);
        }
        log.info("预报产品写入: station={}, model={}, issue={}, 时效数={}",
                stationCode, model, issueTime, series.size());
    }
}
