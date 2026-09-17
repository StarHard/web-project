package com.campus.meteo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.meteo.entity.AlertNotify;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警通知明细 Mapper
 */
@Mapper
public interface AlertNotifyMapper extends BaseMapper<AlertNotify> {
}
