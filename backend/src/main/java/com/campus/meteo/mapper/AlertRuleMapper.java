package com.campus.meteo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.meteo.entity.AlertRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警规则 Mapper
 */
@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRule> {
}
