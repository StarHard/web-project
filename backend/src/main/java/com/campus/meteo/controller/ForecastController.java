package com.campus.meteo.controller;

import com.campus.meteo.agent.forecast.ForecastAgent;
import com.campus.meteo.agent.forecast.VerificationService;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.ForecastResp;
import com.campus.meteo.influx.FcstReader;
import com.campus.meteo.mapper.StationMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Map;

/**
 * 预报服务接口
 * 注意：GET /forecasts 在安全白名单中（公开），检验接口需登录
 */
@Tag(name = "精细化预报")
@RestController
@RequestMapping("/forecasts")
@RequiredArgsConstructor
public class ForecastController {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private final FcstReader fcstReader;
    private final VerificationService verificationService;
    private final ForecastAgent forecastAgent;
    private final StationMapper stationMapper;

    @Operation(summary = "站点预报产品（0-72h 逐小时）")
    @GetMapping
    public Result<ForecastResp> forecast(@RequestParam String stationCode,
                                         @RequestParam(defaultValue = "stat") String model,
                                         @RequestParam(defaultValue = "72") int range) {
        range = Math.min(Math.max(range, 1), 72);
        Instant now = Instant.now();
        Map<Instant, Map<String, Double>> series =
                fcstReader.query(stationCode, model, now, now.plusSeconds((long) range * 3600));

        var points = new ArrayList<ForecastResp.Point>();
        Instant latestIssue = null;
        for (var entry : series.entrySet()) {
            points.add(ForecastResp.Point.builder()
                    .time(TIME_FMT.format(entry.getKey()))
                    .elements(entry.getValue())
                    .build());
            latestIssue = entry.getKey();
        }
        return Result.ok(ForecastResp.builder()
                .stationCode(stationCode)
                .model(model)
                .issueTime(latestIssue != null ? TIME_FMT.format(latestIssue) : null)
                .rangeHours(range)
                .points(points)
                .build());
    }

    @Operation(summary = "预报检验评分（MAE/RMSE/TS）")
    @GetMapping("/verification")
    @PreAuthorize("hasAuthority('forecast:view')")
    public Result<Map<String, Object>> verification(@RequestParam String stationCode,
                                                    @RequestParam(defaultValue = "stat") String model,
                                                    @RequestParam(defaultValue = "7") int days) {
        days = Math.min(Math.max(days, 1), 30);
        return Result.ok(verificationService.verify(stationCode, model, days));
    }

    @Operation(summary = "手动触发预报生成（stationCode 为空则全部站点）")
    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('forecast:view')")
    public Result<Map<String, Object>> generate(@RequestParam(required = false) String stationCode) {
        int success = 0, total = 0;
        if (stationCode != null && !stationCode.isBlank()) {
            com.campus.meteo.entity.Station station = stationMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.campus.meteo.entity.Station>()
                            .eq(com.campus.meteo.entity.Station::getStationCode, stationCode));
            if (station == null) {
                return Result.fail(com.campus.meteo.common.result.ErrorCode.NOT_FOUND, "站点不存在");
            }
            total = 1;
            success = forecastAgent.generate(station) ? 1 : 0;
        } else {
            var stations = stationMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.campus.meteo.entity.Station>()
                            .eq(com.campus.meteo.entity.Station::getStatus, 1));
            total = stations.size();
            for (var station : stations) {
                if (forecastAgent.generate(station)) {
                    success++;
                }
            }
        }
        return Result.ok(Map.of("total", total, "success", success));
    }
}
