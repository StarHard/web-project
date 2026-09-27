package com.campus.meteo.controller;

import com.campus.meteo.agent.forecast.ForecastAgent;
import com.campus.meteo.agent.forecast.VerificationService;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.ForecastCompareResp;
import com.campus.meteo.dto.ForecastResp;
import com.campus.meteo.dto.ForecastRevisionReq;
import com.campus.meteo.influx.FcstReader;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.service.ForecastService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
    private final ForecastService forecastService;
    private final StationMapper stationMapper;

    @Operation(summary = "站点预报产品（0-72h 逐小时）")
    @GetMapping
    public Result<ForecastResp> forecast(@RequestParam String stationCode,
                                         @RequestParam(defaultValue = "stat") String model,
                                         @RequestParam(defaultValue = "72") int range) {
        range = Math.min(Math.max(range, 1), 72);
        Instant now = Instant.now();
        // 用 queryAll 取原始发布记录：query() 内部只保留最新一次发布，却把发布时刻丢掉了。
        // 原实现只能把循环里最后一个「目标时刻」当作发布时间，接口因而返回一个未来时间
        // （9-27 查询会显示「发布时间 9-30」）。这里自行按 issue 过滤，顺带取回真正的发布时刻。
        List<FcstReader.FcstPoint> published =
                fcstReader.queryAll(stationCode, model, now, now.plusSeconds((long) range * 3600));
        long latestIssue = published.stream().mapToLong(FcstReader.FcstPoint::issue).max().orElse(Long.MIN_VALUE);

        Map<Instant, Map<String, Double>> series = new TreeMap<>();
        for (FcstReader.FcstPoint point : published) {
            if (point.issue() == latestIssue) {
                series.put(point.targetTime(), point.elements());
            }
        }

        var points = new ArrayList<ForecastResp.Point>();
        for (var entry : series.entrySet()) {
            points.add(ForecastResp.Point.builder()
                    .time(TIME_FMT.format(entry.getKey()))
                    .elements(entry.getValue())
                    .build());
        }
        return Result.ok(ForecastResp.builder()
                .stationCode(stationCode)
                .model(model)
                .issueTime(latestIssue == Long.MIN_VALUE ? null : TIME_FMT.format(Instant.ofEpochSecond(latestIssue)))
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

    @Operation(summary = "预报回算（重演历史预报，为准确率检验产出样本）")
    @PostMapping("/backtest")
    @PreAuthorize("hasAuthority('forecast:view')")
    public Result<Map<String, Object>> backtest(@RequestParam(required = false) String stationCode,
                                                @RequestParam(defaultValue = "3") int days) {
        days = Math.min(Math.max(days, 1), 7);
        var stations = stationMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.campus.meteo.entity.Station>()
                        .eq(com.campus.meteo.entity.Station::getStatus, 1)
                        .eq(stationCode != null && !stationCode.isBlank(),
                                com.campus.meteo.entity.Station::getStationCode, stationCode));
        int issues = 0;
        for (var station : stations) {
            issues += forecastAgent.backtest(station, days);
        }
        return Result.ok(Map.of("stations", stations.size(), "issues", issues, "days", days));
    }

    @Operation(summary = "多模型预报对比曲线（stat/ml/manual）")
    @GetMapping("/model-compare")
    @PreAuthorize("hasAuthority('forecast:view')")
    public Result<ForecastCompareResp> modelCompare(@RequestParam String stationCode,
                                                    @RequestParam(defaultValue = "temp") String element,
                                                    @RequestParam(defaultValue = "72") int range) {
        return Result.ok(forecastService.compare(stationCode, element, range));
    }

    @Operation(summary = "预报订正（路径 id 为预报目标时刻的 epoch 秒）")
    @PostMapping("/{id}/revisions")
    @PreAuthorize("hasAuthority('forecast:order')")
    public Result<Long> revise(@PathVariable long id, @Valid @RequestBody ForecastRevisionReq req) {
        return Result.ok(forecastService.revise(id, req));
    }
}
