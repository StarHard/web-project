package com.campus.meteo.service;

import com.campus.meteo.dto.ForecastCompareResp;
import com.campus.meteo.dto.ForecastRevisionReq;

/**
 * 预报对比与人工订正服务
 */
public interface ForecastService {

    /** 多模型预报对比（同一要素的各模型曲线） */
    ForecastCompareResp compare(String stationCode, String element, int rangeHours);

    /**
     * 预报订正：留痕 forecast_order 并以 manual 模型重新发布该时次
     *
     * @param targetEpochSecond 预报目标时刻（epoch 秒），即路径参数 id
     * @return 订正记录ID
     */
    Long revise(long targetEpochSecond, ForecastRevisionReq req);
}