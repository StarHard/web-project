package com.campus.meteo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.meteo.entity.Station;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站点 Mapper
 */
@Mapper
public interface StationMapper extends BaseMapper<Station> {
}
