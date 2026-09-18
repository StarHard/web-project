package com.campus.meteo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.meteo.entity.Device;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 设备 Mapper
 */
@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    /**
     * 设备编码占用检查（包含已逻辑删除的记录）
     *
     * 唯一索引 uk_device_code 对物理行生效，逻辑删除并不会释放编码。
     */
    @Select("<script>SELECT COUNT(1) FROM device WHERE device_code = #{deviceCode}"
            + "<if test='excludeId != null'> AND id &lt;&gt; #{excludeId}</if></script>")
    long countByCodeIncludingDeleted(@Param("deviceCode") String deviceCode,
                                     @Param("excludeId") Long excludeId);
}