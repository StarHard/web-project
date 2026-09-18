package com.campus.meteo.controller;

import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsReader;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.agent.collector.CollectorAgent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实时监测接口（白名单公开）
 */
@Slf4j
@Tag(name = "实时监测")
@RestController
@RequestMapping("/realtime")
@RequiredArgsConstructor
public class RealtimeController {

    private final StringRedisTemplate redisTemplate;
    private final ObsReader obsReader;

    @Operation(summary = "站点最新观测数据")
    @GetMapping("/latest")
    public Result<Map<String, Object>> latest(@RequestParam String stationCode) {
        Map<String, Object> data = new LinkedHashMap<>();
        // 优先读 Redis 缓存的原始报文，降级查 InfluxDB
        String payload = redisTemplate.opsForValue().get(CollectorAgent.REDIS_KEY_LATEST + stationCode);
        if (payload != null) {
            data.put("source", "cache");
            data.put("payload", payload);
        } else {
            ObsData obs = obsReader.queryLatest(stationCode);
            if (obs == null) {
                return Result.ok(Map.of("source", "none"));
            }
            data.put("source", "influx");
            data.put("ts", obs.getTs().toString());
            data.put("elements", obs.getElements());
        }
        return Result.ok(data);
    }

    @Operation(summary = "站点要素曲线（最近N小时，支持聚合）")
    @GetMapping("/curve")
    public Result<Object> curve(@RequestParam String stationCode,
                                @RequestParam(defaultValue = "24") int hours,
                                @RequestParam(defaultValue = "auto") String granularity) {
        hours = Math.min(Math.max(hours, 1), 168);
        Instant start = Instant.now().minusSeconds((long) hours * 3600);
        return Result.ok(obsReader.queryRangeAggregated(stationCode, start, Instant.now(), granularity));
    }
}
