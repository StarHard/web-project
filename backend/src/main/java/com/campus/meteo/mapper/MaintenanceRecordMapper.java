package com.campus.meteo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.meteo.entity.MaintenanceRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 运维记录 Mapper
 */
@Mapper
public interface MaintenanceRecordMapper extends BaseMapper<MaintenanceRecord> {
}