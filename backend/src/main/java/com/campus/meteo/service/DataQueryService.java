package com.campus.meteo.service;

import com.campus.meteo.dto.HistoryResp;
import com.campus.meteo.dto.StatsResp;

/**
 * 历史数据查询与统计服务（数据源为 InfluxDB 时序库）
 */
public interface DataQueryService {

    /** 历史时序数据（支持 min/hour/day 聚合） */
    HistoryResp history(Long stationId, String elements, String startTime, String endTime,
                        String granularity, String qcFlag);

    /** 日统计（均值/极值及出现时间） */
    StatsResp daily(Long stationId, String date);

    /** 月统计 */
    StatsResp monthly(Long stationId, String month);

    /** 年统计 */
    StatsResp yearly(Long stationId, String year);

    /** 极值统计 */
    StatsResp extreme(Long stationId, String element, String startTime, String endTime);

    /** 气候平均值对比（同期多年） */
    StatsResp climate(Long stationId, Integer month, String element);
}