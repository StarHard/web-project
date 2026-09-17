package com.campus.meteo.service;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.AlertRuleSaveReq;
import com.campus.meteo.entity.AlertRecord;
import com.campus.meteo.entity.AlertRule;

import java.util.Map;

/**
 * 告警服务
 */
public interface AlertService {

    /** 规则分页查询 */
    PageResult<AlertRule> pageRules(long pageNum, long pageSize, Long stationId, Integer alertType, Integer status);

    /** 新增规则 */
    void createRule(AlertRuleSaveReq req);

    /** 修改规则 */
    void updateRule(Long id, AlertRuleSaveReq req);

    /** 删除规则（逻辑删除） */
    void deleteRule(Long id);

    /** 启用/停用规则 */
    void toggleRuleStatus(Long id, boolean enabled);

    /** 告警记录分页查询 */
    PageResult<AlertRecord> pageRecords(long pageNum, long pageSize, Long stationId, Integer level,
                                        Integer alertType, Integer status, String startTime, String endTime);

    /** 告警统计：按类型/等级/站点汇总 */
    Map<String, Object> stat(String startTime, String endTime);

    /** 手动解除告警 */
    void relieve(Long id);
}
