package com.campus.meteo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.meteo.entity.Station;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 站点 Mapper
 */
@Mapper
public interface StationMapper extends BaseMapper<Station> {

    /**
     * 站点编码占用检查（包含已逻辑删除的记录）
     *
     * 唯一索引 uk_station_code 对物理行生效，逻辑删除并不会释放编码，
     * 因此校验必须绕开逻辑删除过滤，否则重复插入将直接抛 DuplicateKeyException。
     */
    @Select("<script>SELECT COUNT(1) FROM station WHERE station_code = #{stationCode}"
            + "<if test='excludeId != null'> AND id &lt;&gt; #{excludeId}</if></script>")
    long countByCodeIncludingDeleted(@Param("stationCode") String stationCode,
                                     @Param("excludeId") Long excludeId);
}