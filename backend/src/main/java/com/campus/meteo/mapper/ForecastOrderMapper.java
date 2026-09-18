package com.campus.meteo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.meteo.entity.ForecastOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预报订正记录 Mapper
 */
@Mapper
public interface ForecastOrderMapper extends BaseMapper<ForecastOrder> {
}