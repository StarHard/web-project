package com.campus.meteo.agent.realtime;

import com.campus.meteo.influx.ObsData;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 实时数据缓存：站点最新一条**质控后**观测，供 /realtime/latest 与 /stations/{id} 高频读取。
 *
 * 写入点从采集侧移到质控之后。采集侧写入时 qc_flag 还是 raw，会把随后被质控判为可疑的
 * 尖峰值缓存下来并对外返回；而同一接口缓存缺失时降级查 InfluxDB 会按质控标记过滤，
 * 两条路径口径不一致（同一个接口命中缓存与不命中缓存给出不同的值）。
 *
 * 键设 24 小时过期：降级查询同样只回溯 24 小时，不设过期会让长期离线站点
 * 一直返回陈旧缓存，两条路径的时效口径又会不一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeCache {

    /** 缓存键前缀 */
    public static final String KEY_PREFIX = "meteo:realtime:";

    /** 与 ObsReader.queryLatest 的回溯窗口保持一致 */
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 缓存质控后的最新观测 */
    public void save(ObsData obs) {
        if (obs == null || obs.getStationCode() == null || obs.getTs() == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + obs.getStationCode(),
                    objectMapper.writeValueAsString(obs), TTL);
        } catch (Exception e) {
            // 缓存只影响读取性能，写失败不应影响采集/质控主链路
            log.warn("实时缓存写入失败: station={}, err={}", obs.getStationCode(), e.getMessage());
        }
    }

    /** 读取站点最新缓存，无缓存或解析失败返回 null */
    public ObsData find(String stationCode) {
        if (stationCode == null || stationCode.isBlank()) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + stationCode);
            return json == null ? null : objectMapper.readValue(json, ObsData.class);
        } catch (Exception e) {
            log.warn("实时缓存解析失败: station={}, err={}", stationCode, e.getMessage());
            return null;
        }
    }
}
