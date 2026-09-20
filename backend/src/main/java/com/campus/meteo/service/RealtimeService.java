package com.campus.meteo.service;

import com.campus.meteo.dto.RealtimeCompareResp;
import com.campus.meteo.dto.RealtimeLatestResp;

/**
 * 实时监测服务
 */
public interface RealtimeService {

    /**
     * 站点最新观测（优先读实时缓存，缺失时降级查时序库）
     *
     * 两条路径的质控口径一致：缓存由质控Agent写入，降级查询按质控标记过滤。
     */
    RealtimeLatestResp latest(String stationCode);

    /**
     * 多站点同要素对比（FR-RT-04）
     *
     * @param stationIds  站点ID，逗号分隔（最多 6 个）
     * @param element     对比要素
     * @param startTime   开始时间（yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd），缺省为 24 小时前
     * @param endTime     结束时间，缺省为当前时刻
     * @param granularity 聚合粒度（raw/5m/15m/1h/1d/auto），缺省 auto
     */
    RealtimeCompareResp compare(String stationIds, String element, String startTime,
                                String endTime, String granularity);
}
