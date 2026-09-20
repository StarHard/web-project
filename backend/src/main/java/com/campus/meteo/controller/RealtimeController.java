package com.campus.meteo.controller;

import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsReader;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.agent.collector.CollectorAgent;
import com.campus.meteo.dto.RealtimeCompareResp;
import com.campus.meteo.service.RealtimeService;
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
    private final RealtimeService realtimeService;

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

    /**
     * 多站点同要素对比（FR-RT-04）
     *
     * 该接口不在 PUBLIC_GET 白名单内，需登录后访问（与接口规范 2.3 一致）。
     */
    @Operation(summary = "多站点同要素对比", description = "时间轴由服务端取并集，各站点序列按此对齐，缺测为 null")
    @GetMapping("/compare")
    public Result<RealtimeCompareResp> compare(@RequestParam String stationIds,
                                               @RequestParam String element,
                                               @RequestParam(required = false) String startTime,
                                               @RequestParam(required = false) String endTime,
                                               @RequestParam(defaultValue = "auto") String granularity) {
        return Result.ok(realtimeService.compare(stationIds, element, startTime, endTime, granularity));
    }
}
