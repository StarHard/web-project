package com.campus.meteo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.meteo.agent.realtime.RealtimeCache;
import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.DeviceResp;
import com.campus.meteo.dto.StationMapResp;
import com.campus.meteo.dto.StationResp;
import com.campus.meteo.dto.StationSaveReq;
import com.campus.meteo.entity.AlertRecord;
import com.campus.meteo.entity.Device;
import com.campus.meteo.entity.Station;
import com.campus.meteo.influx.ObsData;
import com.campus.meteo.mapper.AlertRecordMapper;
import com.campus.meteo.mapper.DeviceMapper;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.service.StationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 站点服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StationServiceImpl implements StationService {

    private final StationMapper stationMapper;
    private final DeviceMapper deviceMapper;
    private final AlertRecordMapper alertRecordMapper;
    private final RealtimeCache realtimeCache;
    private final ObjectMapper objectMapper;

    @Override
    public PageResult<StationResp> page(long pageNum, long pageSize, String keyword, Integer status, Integer onlineFlag) {
        Page<Station> page = stationMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<Station>()
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(Station::getName, keyword)
                                .or().like(Station::getStationCode, keyword))
                        .eq(status != null, Station::getStatus, status)
                        .eq(onlineFlag != null, Station::getOnlineFlag, onlineFlag)
                        .orderByAsc(Station::getId));
        List<StationResp> list = page.getRecords().stream()
                .map(station -> toResp(station, false))
                .toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), list);
    }

    @Override
    public StationResp detail(Long id) {
        Station station = stationMapper.selectById(id);
        if (station == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "站点不存在");
        }
        return toResp(station, true);
    }

    @Override
    public void create(StationSaveReq req) {
        ensureCodeUnique(req.getStationCode(), null);
        Station station = new Station();
        copy(req, station);
        if (station.getStatus() == null) {
            station.setStatus(1);
        }
        station.setOnlineFlag(0);
        stationMapper.insert(station);
    }

    @Override
    public void update(Long id, StationSaveReq req) {
        if (stationMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "站点不存在");
        }
        ensureCodeUnique(req.getStationCode(), id);
        Station station = new Station();
        copy(req, station);
        station.setId(id);
        stationMapper.updateById(station);
    }

    @Override
    public void delete(Long id) {
        if (stationMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "站点不存在");
        }
        stationMapper.deleteById(id);
    }

    @Override
    public List<StationMapResp> mapData() {
        List<Station> stations = stationMapper.selectList(new LambdaQueryWrapper<Station>()
                .ne(Station::getStatus, 0)
                .orderByAsc(Station::getId));

        // 未解除告警（status=0 进行中）按站点取最高等级作为角标
        Map<Long, Integer> alertLevelByStation = alertRecordMapper.selectList(
                        new LambdaQueryWrapper<AlertRecord>().eq(AlertRecord::getStatus, 0))
                .stream()
                .collect(Collectors.toMap(AlertRecord::getStationId, AlertRecord::getLevel,
                        Math::max, java.util.LinkedHashMap::new));

        return stations.stream().map(station -> {
            StationMapResp resp = new StationMapResp();
            resp.setId(station.getId());
            resp.setStationCode(station.getStationCode());
            resp.setName(station.getName());
            resp.setLongitude(station.getLongitude());
            resp.setLatitude(station.getLatitude());
            resp.setOnlineFlag(station.getOnlineFlag());
            resp.setStatus(station.getStatus());
            resp.setAlertLevel(alertLevelByStation.getOrDefault(station.getId(), 0));
            return resp;
        }).toList();
    }

    /** 站点编码唯一性校验（excludeId 用于更新时排除自身；含已逻辑删除记录） */
    private void ensureCodeUnique(String stationCode, Long excludeId) {
        if (stationMapper.countByCodeIncludingDeleted(stationCode, excludeId) > 0) {
            throw new BizException(ErrorCode.STATION_CODE_EXISTS,
                    "站点编码已存在（已删除站点的编码仍被唯一索引占用，请更换编码或还原原站点）");
        }
    }

    private void copy(StationSaveReq req, Station station) {
        station.setStationCode(req.getStationCode());
        station.setName(req.getName());
        station.setProvince(req.getProvince());
        station.setCity(req.getCity());
        station.setDistrict(req.getDistrict());
        station.setLongitude(req.getLongitude());
        station.setLatitude(req.getLatitude());
        station.setAltitude(req.getAltitude());
        station.setStationType(req.getStationType());
        station.setStatus(req.getStatus());
        station.setCommissionDate(req.getCommissionDate());
    }

    private StationResp toResp(Station station, boolean withDevices) {
        StationResp resp = new StationResp();
        resp.setId(station.getId());
        resp.setStationCode(station.getStationCode());
        resp.setName(station.getName());
        resp.setProvince(station.getProvince());
        resp.setCity(station.getCity());
        resp.setDistrict(station.getDistrict());
        resp.setLongitude(station.getLongitude());
        resp.setLatitude(station.getLatitude());
        resp.setAltitude(station.getAltitude());
        resp.setStationType(station.getStationType());
        resp.setStatus(station.getStatus());
        resp.setOnlineFlag(station.getOnlineFlag());
        resp.setLastReportTime(station.getLastReportTime());
        resp.setCommissionDate(station.getCommissionDate());

        if (withDevices) {
            resp.setDevices(deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                            .eq(Device::getStationId, station.getId())
                            .orderByAsc(Device::getId))
                    .stream().map(this::toDeviceResp).toList());
        }
        resp.setLatest(readLatest(station.getStationCode()));
        return resp;
    }

    private DeviceResp toDeviceResp(Device device) {
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

    /** 读取质控Agent写入的最新观测缓存，缓存缺失不影响主流程 */
    private JsonNode readLatest(String stationCode) {
        ObsData obs = realtimeCache.find(stationCode);
        if (obs == null) {
            return null;
        }
        // 只输出对外口径需要的字段，不透出 msgId 等链路内部字段
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ts", obs.getTs() == null ? null : obs.getTs().toString());
        summary.put("qcFlag", obs.getQcFlag());
        summary.put("elements", obs.getElements());
        return objectMapper.valueToTree(summary);
    }
}