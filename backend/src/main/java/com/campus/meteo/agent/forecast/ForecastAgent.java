package com.campus.meteo.agent.forecast;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.meteo.entity.Station;
import com.campus.meteo.influx.FcstWriter;
import com.campus.meteo.influx.ObsReader;
import com.campus.meteo.mapper.StationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 预报Agent：
 * 每小时第10分钟（避开整点观测上报）为所有正常站点生成 0-72h 逐小时预报，
 * 采用统计降尺度基线模型，产品写入 InfluxDB fcst（model=stat）。
 * 预报发布事件可选发送 MQ topic.forecast.ready（当前阶段由前端轮询，暂不发送）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForecastAgent {

    /** 预报模型标识 */
    public static final String MODEL_STAT = "stat";
    /** 预报时效（小时） */
    private static final int FORECAST_HOURS = 72;
    /** 历史样本长度（天） */
    private static final int HISTORY_DAYS = 7;

    private final StationMapper stationMapper;
    private final ObsReader obsReader;
    private final StatisticalForecastModel model;
    private final FcstWriter fcstWriter;

    /** 定时生成：每小时第10分钟 */
    @Scheduled(cron = "0 10 * * * *")
    public void generateAll() {
        List<Station> stations = stationMapper.selectList(new LambdaQueryWrapper<Station>()
                .eq(Station::getStatus, 1));
        int success = 0;
        for (Station station : stations) {
            try {
                if (generate(station)) {
                    success++;
                }
            } catch (Exception e) {
                log.error("预报生成失败: station={}, err={}", station.getStationCode(), e.getMessage(), e);
            }
        }
        log.info("本轮预报生成完成: 成功 {}/{} 个站点", success, stations.size());
    }

    /** 单站点预报生成（返回是否成功） */
    public boolean generate(Station station) {
        Instant now = Instant.now();
        var history = obsReader.queryRange(station.getStationCode(),
                now.minusSeconds((long) HISTORY_DAYS * 86400), now);
        if (history.size() < 3) {
            log.warn("站点观测样本不足，跳过预报: station={}, samples={}", station.getStationCode(), history.size());
            return false;
        }
        Map<Instant, Map<String, Double>> series = model.forecast(history, FORECAST_HOURS);
        if (series.isEmpty()) {
            return false;
        }
        fcstWriter.writeFcst(station.getStationCode(), MODEL_STAT, now, series);
        return true;
    }
}
