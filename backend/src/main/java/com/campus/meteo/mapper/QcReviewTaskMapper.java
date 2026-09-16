package com.campus.meteo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.meteo.entity.QcReviewTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 质控审核任务 Mapper
 */
@Mapper
public interface QcReviewTaskMapper extends BaseMapper<QcReviewTask> {
}
