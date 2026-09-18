package com.campus.meteo.service;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.StationMapResp;
import com.campus.meteo.dto.StationResp;
import com.campus.meteo.dto.StationSaveReq;

import java.util.List;

/**
 * 站点服务
 */
public interface StationService {

    /** 站点分页（keyword 匹配名称或编码） */
    PageResult<StationResp> page(long pageNum, long pageSize, String keyword, Integer status, Integer onlineFlag);

    /** 站点详情（含设备清单与最新数据摘要） */
    StationResp detail(Long id);

    void create(StationSaveReq req);

    void update(Long id, StationSaveReq req);

    void delete(Long id);

    /** 地图聚合：坐标 + 在线状态 + 未解除告警角标 */
    List<StationMapResp> mapData();
}