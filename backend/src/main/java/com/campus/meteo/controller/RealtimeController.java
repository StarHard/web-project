package com.campus.meteo.controller;

import com.campus.meteo.influx.ObsReader;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.RealtimeCompareResp;
import com.campus.meteo.dto.RealtimeLatestResp;
import com.campus.meteo.service.RealtimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 实时监测接口
 *
 * /realtime/latest 与 /realtime/curve 为公开只读接口（见 SecurityConfig 的 PUBLIC_GET），
 * /realtime/compare 需登录。
 */
@Slf4j
@Tag(name = "实时监测")
@RestController
@RequestMapping("/realtime")
@RequiredArgsConstructor
public class RealtimeController {

    private final ObsReader obsReader;
    private final RealtimeService realtimeService;

    @Operation(summary = "站点最新观测数据", description = "优先读实时缓存，缺失时降级查时序库，两条路径口径一致")
    @GetMapping("/latest")
    public Result<RealtimeLatestResp> latest(@RequestParam String stationCode) {
        return Result.ok(realtimeService.latest(stationCode));
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
