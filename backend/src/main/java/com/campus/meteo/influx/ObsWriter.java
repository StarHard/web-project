package com.campus.meteo.influx;

import com.campus.meteo.common.constant.QcFlag;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.WriteOptions;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 观测数据写入 InfluxDB 的统一入口（规范：禁止散落各处直接写时序库）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObsWriter {

    public static final String MEASUREMENT_OBS_MIN = "obs_min";
    public static final String TAG_STATION = "station_code";
    public static final String TAG_QC = "qc_flag";

    private final InfluxDBClient client;
    private final InfluxProperties properties;

    /**
     * 写入一条观测数据（阻塞写入，分钟级频率下性能足够）
     */
    public void writeObs(ObsData obs) {
        Instant ts = obs.getTs() != null ? obs.getTs() : Instant.now();
        Point point = Point.measurement(MEASUREMENT_OBS_MIN)
                .addTag(TAG_STATION, obs.getStationCode())
                .addTag(TAG_QC, obs.getQcFlag() != null ? obs.getQcFlag() : QcFlag.RAW.getValue())
                .time(ts, WritePrecision.S);
        obs.getElements().forEach((k, v) -> {
            if (v != null && !v.isNaN()) {
                point.addField(k, v);
            }
        });
        client.getWriteApiBlocking().writePoint(properties.getBucket(), properties.getOrg(), point);
        log.debug("写入InfluxDB: station={}, qc={}, ts={}, msgId={}",
                obs.getStationCode(), obs.getQcFlag(), ts, obs.getMsgId());
    }

    /**
     * 异步批量写入 API（高频场景使用，调用方负责关闭）
     */
    public WriteApi asyncWriteApi() {
        return client.makeWriteApi(WriteOptions.builder().batchSize(500).flushInterval(1000).build());
    }

    /** 阻塞写入 API（查询前强制刷新场景） */
    public WriteApiBlocking blockingApi() {
        return client.getWriteApiBlocking();
    }
}
