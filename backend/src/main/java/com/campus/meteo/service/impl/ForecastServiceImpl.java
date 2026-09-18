package com.campus.meteo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.dto.ForecastCompareResp;
import com.campus.meteo.dto.ForecastPoint;
import com.campus.meteo.dto.ForecastRevisionReq;
import com.campus.meteo.entity.ForecastOrder;
import com.campus.meteo.entity.Station;
import com.campus.meteo.influx.FcstReader;
import com.campus.meteo.influx.FcstWriter;
import com.campus.meteo.mapper.ForecastOrderMapper;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.security.SecurityUtils;
import com.campus.meteo.service.ForecastService;
import com.campus.meteo.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 预报对比与人工订正实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForecastServiceImpl implements ForecastService {

    /** 人工订正模型标识（与 FcstWriter 中 model 取值约定一致） */
    private static final String MODEL_MANUAL = "manual";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZONE);

    private final FcstReader fcstReader;
    private final FcstWriter fcstWriter;
    private final StationMapper stationMapper;
    private final ForecastOrderMapper forecastOrderMapper;
    private final OperationLogService operationLogService;

    @Override
    public ForecastCompareResp compare(String stationCode, String element, int rangeHours) {
        Station station = resolveStation(stationCode);
        int range = Math.min(Math.max(rangeHours, 1), 72);
        Instant now = Instant.now();
        Instant stop = now.plusSeconds((long) range * 3600);

        ForecastCompareResp resp = new ForecastCompareResp();
        resp.setStationCode(station.getStationCode());
        resp.setElement(element);
        resp.setRangeHours(range);

        for (String model : fcstReader.queryModels(station.getStationCode())) {
            Map<Instant, Map<String, Double>> series =
                    fcstReader.query(station.getStationCode(), model, now, stop);
            List<ForecastPoint> points = new ArrayList<>();
            series.forEach((time, elements) -> {
                Double value = elements.get(element);
                if (value != null) {
                    points.add(new ForecastPoint(TIME_FMT.format(time), value));
                }
            });
            // 该模型无此要素数据时不返回空曲线，避免前端图例出现空项
            if (!points.isEmpty()) {
                resp.getModels().put(model, points);
            }
        }
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long revise(long targetEpochSecond, ForecastRevisionReq req) {
        Station station = resolveStation(req.getStationCode());
        LocalDateTime forecastTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(targetEpochSecond), ZONE);

        // 1. 订正留痕（forecast_order），保留原始值与订正依据以备追溯
        ForecastOrder order = new ForecastOrder();
        order.setStationId(station.getId());
        order.setForecastTime(forecastTime);
        order.setElement(req.getElement());
        order.setOriginValue(req.getOriginValue());
        order.setRevisedValue(req.getRevisedValue());
        order.setOrderUserId(SecurityUtils.getCurrentUserId());
        order.setReason(req.getReason());
        forecastOrderMapper.insert(order);

        // 2. 以 manual 模型重新发布该时次：FcstReader 按最新 issue 取值，订正产品即可对外生效
        fcstWriter.writeFcst(station.getStationCode(), MODEL_MANUAL, Instant.now(),
                Map.of(forecastTime.atZone(ZONE).toInstant(),
                        Map.of(req.getElement(), req.getRevisedValue().doubleValue())));

        operationLogService.record("预报订正", "订正预报",
                "station=%s, element=%s, %s→%s".formatted(req.getStationCode(), req.getElement(),
                        req.getOriginValue(), req.getRevisedValue()));
        log.info("预报订正完成: station={}, element={}, time={}, {}→{}",
                req.getStationCode(), req.getElement(), forecastTime,
                req.getOriginValue(), req.getRevisedValue());
        return order.getId();
    }

    private Station resolveStation(String stationCode) {
        Station station = stationMapper.selectOne(new LambdaQueryWrapper<Station>()
                .eq(Station::getStationCode, stationCode));
        if (station == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "站点不存在: " + stationCode);
        }
        return station;
    }
}