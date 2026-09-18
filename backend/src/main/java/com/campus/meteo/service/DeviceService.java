package com.campus.meteo.service;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.DeviceResp;
import com.campus.meteo.dto.DeviceSaveReq;
import com.campus.meteo.dto.MaintenanceSaveReq;
import com.campus.meteo.entity.MaintenanceRecord;

import java.util.List;

/**
 * 设备与运维记录服务
 */
public interface DeviceService {

    /** 站点下的设备清单 */
    List<DeviceResp> listByStation(Long stationId);

    void create(DeviceSaveReq req);

    void update(Long id, DeviceSaveReq req);

    void delete(Long id);

    /** 运维记录分页 */
    PageResult<MaintenanceRecord> pageMaintenance(long pageNum, long pageSize, Long stationId, Long deviceId);

    void createMaintenance(MaintenanceSaveReq req);
}