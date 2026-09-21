package com.campus.meteo.agent.alert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.meteo.entity.AlertRule;
import com.campus.meteo.entity.Station;
import com.campus.meteo.mapper.AlertRuleMapper;
import com.campus.meteo.mapper.StationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 告警上下文缓存：启用中的告警规则 + 站点映射，30 秒刷新一次
 * （告警为分钟级判定场景，短暂缓存可接受；规则修改后最长 30 秒生效）
 */
@Component
@RequiredArgsConstructor
public class AlertContextCache {

    private final AlertRuleMapper alertRuleMapper;
    private final StationMapper stationMapper;

    private volatile List<AlertRule> enabledRules = List.of();
    private volatile Map<Long, String> stationCodeById = Map.of();
    private volatile Map<Long, String> stationNameById = Map.of();
    private volatile Map<String, Long> stationIdByCode = Map.of();
    private volatile long cacheAt;

    /** 启用中的告警规则 */
    public List<AlertRule> getEnabledRules() {
        ensureCache();
        return enabledRules;
    }

    /** 站点编码 → 站点ID */
    public Long getStationId(String stationCode) {
        ensureCache();
        return stationIdByCode.get(stationCode);
    }

    /** 站点ID → 站点编码 */
    public String getStationCode(Long stationId) {
        ensureCache();
        return stationCodeById.get(stationId);
    }

    /** 站点ID → 站点名称（预警文案面向值班人员，用名称而非编码更易识别） */
    public String getStationName(Long stationId) {
        ensureCache();
        return stationNameById.get(stationId);
    }

    private synchronized void ensureCache() {
        long now = System.currentTimeMillis();
        if (cacheAt > 0 && now - cacheAt < 30_000) {
            return;
        }
        enabledRules = alertRuleMapper.selectList(new LambdaQueryWrapper<AlertRule>()
                .eq(AlertRule::getStatus, 1));
        List<Station> stations = stationMapper.selectList(new LambdaQueryWrapper<Station>());
        stationCodeById = stations.stream()
                .collect(Collectors.toMap(Station::getId, Station::getStationCode, (a, b) -> a));
        stationNameById = stations.stream()
                .collect(Collectors.toMap(Station::getId, Station::getName, (a, b) -> a));
        stationIdByCode = stations.stream()
                .collect(Collectors.toMap(Station::getStationCode, Station::getId, (a, b) -> a));
        cacheAt = now;
    }

    /** 强制刷新（规则修改后调用） */
    public void refresh() {
        cacheAt = 0;
        ensureCache();
    }
}
