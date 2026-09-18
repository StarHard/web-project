package com.campus.meteo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.meteo.entity.AlertSubscribe;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 告警订阅 Mapper
 */
@Mapper
public interface AlertSubscribeMapper extends BaseMapper<AlertSubscribe> {

    /**
     * 复活已逻辑删除的同维度订阅
     *
     * 唯一索引 uk_subscribe(user_id, station_id, alert_type) 对物理行生效，
     * 取消订阅后再订阅同一维度必须复用原行，否则将撞唯一键。
     */
    @Update("UPDATE alert_subscribe SET deleted = 0, channels = #{channels}, update_time = NOW() "
            + "WHERE user_id = #{userId} AND station_id = #{stationId} AND alert_type = #{alertType} AND deleted = 1")
    int restore(@Param("userId") Long userId,
                @Param("stationId") Long stationId,
                @Param("alertType") Integer alertType,
                @Param("channels") String channels);
}