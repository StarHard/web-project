package com.campus.meteo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.meteo.agent.alert.AlertContextCache;
import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.AlertRuleSaveReq;
import com.campus.meteo.entity.AlertRecord;
import com.campus.meteo.entity.AlertRule;
import com.campus.meteo.mapper.AlertRecordMapper;
import com.campus.meteo.mapper.AlertRuleMapper;
import com.campus.meteo.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 告警服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertServiceImpl implements AlertService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> VALID_CHANNELS = Set.of("web", "sms", "email", "wechat");

    private final AlertRuleMapper alertRuleMapper;
    private final AlertRecordMapper alertRecordMapper;
    private final AlertContextCache alertContextCache;

    @Override
    public PageResult<AlertRule> pageRules(long pageNum, long pageSize, Long stationId, Integer alertType, Integer status) {
        Page<AlertRule> page = alertRuleMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<AlertRule>()
                        .eq(stationId != null, AlertRule::getStationId, stationId)
                        .eq(alertType != null, AlertRule::getAlertType, alertType)
                        .eq(status != null, AlertRule::getStatus, status)
                        .orderByDesc(AlertRule::getId));
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Override
    public void createRule(AlertRuleSaveReq req) {
        validate(req);
        AlertRule rule = toEntity(req);
        rule.setStatus(1);
        alertRuleMapper.insert(rule);
        alertContextCache.refresh();
    }

    @Override
    public void updateRule(Long id, AlertRuleSaveReq req) {
        AlertRule existing = alertRuleMapper.selectById(id);
        if (existing == null) {
            throw new BizException(ErrorCode.ALERT_RULE_NOT_FOUND);
        }
        validate(req);
        AlertRule rule = toEntity(req);
        rule.setId(id);
        alertRuleMapper.updateById(rule);
        alertContextCache.refresh();
    }

    @Override
    public void deleteRule(Long id) {
        if (alertRuleMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.ALERT_RULE_NOT_FOUND);
        }
        alertRuleMapper.deleteById(id);
        alertContextCache.refresh();
    }

    @Override
    public void toggleRuleStatus(Long id, boolean enabled) {
        AlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null) {
            throw new BizException(ErrorCode.ALERT_RULE_NOT_FOUND);
        }
        AlertRule update = new AlertRule();
        update.setId(id);
        update.setStatus(enabled ? 1 : 0);
        alertRuleMapper.updateById(update);
        alertContextCache.refresh();
    }

    @Override
    public PageResult<AlertRecord> pageRecords(long pageNum, long pageSize, Long stationId, Integer level,
                                               Integer alertType, Integer status, String startTime, String endTime) {
        // alertType 存在于规则而非记录上，需先查规则ID集合
        Set<Long> ruleIds = null;
        if (alertType != null) {
            ruleIds = alertRuleMapper.selectList(new LambdaQueryWrapper<AlertRule>()
                            .eq(AlertRule::getAlertType, alertType))
                    .stream().map(AlertRule::getId).collect(Collectors.toSet());
            if (ruleIds.isEmpty()) {
                return PageResult.of(0, pageNum, pageSize, java.util.List.of());
            }
        }
        Page<AlertRecord> page = alertRecordMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<AlertRecord>()
                        .eq(stationId != null, AlertRecord::getStationId, stationId)
                        .eq(level != null, AlertRecord::getLevel, level)
                        .eq(status != null, AlertRecord::getStatus, status)
                        .in(ruleIds != null, AlertRecord::getRuleId, ruleIds)
                        .ge(StringUtils.hasText(startTime), AlertRecord::getAlertTime,
                                StringUtils.hasText(startTime) ? LocalDateTime.parse(startTime, TIME_FMT) : null)
                        .le(StringUtils.hasText(endTime), AlertRecord::getAlertTime,
                                StringUtils.hasText(endTime) ? LocalDateTime.parse(endTime, TIME_FMT) : null)
                        .orderByDesc(AlertRecord::getAlertTime));
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Override
    public Map<String, Object> stat(String startTime, String endTime) {
        LocalDateTime start = StringUtils.hasText(startTime) ? LocalDateTime.parse(startTime, TIME_FMT) : null;
        LocalDateTime end = StringUtils.hasText(endTime) ? LocalDateTime.parse(endTime, TIME_FMT) : null;
        java.util.List<AlertRecord> records = alertRecordMapper.selectList(new LambdaQueryWrapper<AlertRecord>()
                .ge(start != null, AlertRecord::getAlertTime, start)
                .le(end != null, AlertRecord::getAlertTime, end));

        // 按等级统计
        Map<Integer, Long> byLevel = records.stream()
                .collect(Collectors.groupingBy(AlertRecord::getLevel, Collectors.counting()));
        // 按状态统计
        Map<Integer, Long> byStatus = records.stream()
                .collect(Collectors.groupingBy(AlertRecord::getStatus, Collectors.counting()));
        // 按站点统计（TopN 由前端处理）
        Map<Long, Long> byStation = records.stream()
                .collect(Collectors.groupingBy(AlertRecord::getStationId, Collectors.counting()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", records.size());
        result.put("byLevel", byLevel);
        result.put("byStatus", byStatus);
        result.put("byStation", byStation);
        return result;
    }

    @Override
    public void relieve(Long id) {
        AlertRecord record = alertRecordMapper.selectById(id);
        if (record == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "告警记录不存在");
        }
        if (record.getStatus() != 0) {
            throw new BizException(ErrorCode.ALERT_RULE_NOT_FOUND, "告警已解除或已升级，无需重复操作");
        }
        AlertRecord update = new AlertRecord();
        update.setId(id);
        update.setStatus(1);
        update.setRelieveTime(LocalDateTime.now());
        alertRecordMapper.updateById(update);
    }

    /** 业务校验：渠道合法、condition=3 时持续时长必填 */
    private void validate(AlertRuleSaveReq req) {
        if (req.getChannels() != null) {
            for (String channel : req.getChannels()) {
                if (!VALID_CHANNELS.contains(channel)) {
                    throw new BizException(ErrorCode.ALERT_THRESHOLD_INVALID, "非法推送渠道: " + channel);
                }
            }
        }
        if (req.getCondition() == 3 && req.getDurationMin() == null) {
            throw new BizException(ErrorCode.ALERT_THRESHOLD_INVALID, "持续判定条件须配置持续时长");
        }
        if (req.getUpgradeLevel() != null && req.getUpgradeLevel() <= req.getLevel()) {
            throw new BizException(ErrorCode.ALERT_THRESHOLD_INVALID, "升级等级须高于初始等级");
        }
    }

    private AlertRule toEntity(AlertRuleSaveReq req) {
        AlertRule rule = new AlertRule();
        rule.setStationId(req.getStationId());
        rule.setAlertType(req.getAlertType());
        rule.setElement(req.getElement());
        rule.setCondition(req.getCondition());
        rule.setThreshold(req.getThreshold());
        rule.setDurationMin(req.getDurationMin());
        rule.setLevel(req.getLevel());
        rule.setUpgradeLevel(req.getUpgradeLevel());
        rule.setChannels(req.getChannels() == null ? "web" : String.join(",", req.getChannels()));
        return rule;
    }
}
