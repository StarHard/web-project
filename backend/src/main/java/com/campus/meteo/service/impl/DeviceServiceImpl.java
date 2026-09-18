package com.campus.meteo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.DeviceResp;
import com.campus.meteo.dto.DeviceSaveReq;
import com.campus.meteo.dto.MaintenanceSaveReq;
import com.campus.meteo.entity.Device;
import com.campus.meteo.entity.MaintenanceRecord;
import com.campus.meteo.mapper.DeviceMapper;
import com.campus.meteo.mapper.MaintenanceRecordMapper;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 设备与运维记录服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final DeviceMapper deviceMapper;
    private final MaintenanceRecordMapper maintenanceRecordMapper;
    private final StationMapper stationMapper;

    @Override
    public List<DeviceResp> listByStation(Long stationId) {
        return deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                        .eq(stationId != null, Device::getStationId, stationId)
                        .orderByAsc(Device::getId))
                .stream().map(this::toResp).toList();
    }

    @Override
    public void create(DeviceSaveReq req) {
        ensureStationExists(req.getStationId());
        ensureCodeUnique(req.getDeviceCode(), null);
        Device device = new Device();
        copy(req, device);
        if (device.getStatus() == null) {
            device.setStatus(1);
        }
        deviceMapper.insert(device);
    }

    @Override
    public void update(Long id, DeviceSaveReq req) {
        if (deviceMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
        }
        ensureStationExists(req.getStationId());
        ensureCodeUnique(req.getDeviceCode(), id);
        Device device = new Device();
        copy(req, device);
        device.setId(id);
        deviceMapper.updateById(device);
    }

    @Override
    public void delete(Long id) {
        if (deviceMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
        }
        deviceMapper.deleteById(id);
    }

    @Override
    public PageResult<MaintenanceRecord> pageMaintenance(long pageNum, long pageSize, Long stationId, Long deviceId) {
        Page<MaintenanceRecord> page = maintenanceRecordMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<MaintenanceRecord>()
                        .eq(stationId != null, MaintenanceRecord::getStationId, stationId)
                        .eq(deviceId != null, MaintenanceRecord::getDeviceId, deviceId)
                        .orderByDesc(MaintenanceRecord::getMaintDate)
                        .orderByDesc(MaintenanceRecord::getId));
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Override
    public void createMaintenance(MaintenanceSaveReq req) {
        ensureStationExists(req.getStationId());
        MaintenanceRecord record = new MaintenanceRecord();
        record.setStationId(req.getStationId());
        record.setDeviceId(req.getDeviceId());
        record.setType(req.getType());
        record.setContent(req.getContent());
        record.setOperator(req.getOperator());
        record.setMaintDate(req.getMaintDate());
        maintenanceRecordMapper.insert(record);
    }

    private void ensureStationExists(Long stationId) {
        if (stationMapper.selectById(stationId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "站点不存在");
        }
    }

    /** 设备编码唯一性校验（excludeId 用于更新时排除自身；含已逻辑删除记录） */
    private void ensureCodeUnique(String deviceCode, Long excludeId) {
        if (deviceMapper.countByCodeIncludingDeleted(deviceCode, excludeId) > 0) {
            throw new BizException(ErrorCode.DEVICE_CODE_EXISTS,
                    "设备编码已存在（已删除设备的编码仍被唯一索引占用，请更换编码）");
        }
    }

    private void copy(DeviceSaveReq req, Device device) {
        device.setStationId(req.getStationId());
        device.setDeviceCode(req.getDeviceCode());
        device.setDeviceType(req.getDeviceType());
        device.setModel(req.getModel());
        device.setManufacturer(req.getManufacturer());
        device.setInstallDate(req.getInstallDate());
        device.setStatus(req.getStatus());
    }

    private DeviceResp toResp(Device device) {
        DeviceResp resp = new DeviceResp();
        resp.setId(device.getId());
        resp.setStationId(device.getStationId());
        resp.setDeviceCode(device.getDeviceCode());
        resp.setDeviceType(device.getDeviceType());
        resp.setModel(device.getModel());
        resp.setManufacturer(device.getManufacturer());
        resp.setInstallDate(device.getInstallDate());
        resp.setStatus(device.getStatus());
        return resp;
    }
}