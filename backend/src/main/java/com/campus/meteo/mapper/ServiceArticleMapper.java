package com.campus.meteo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.meteo.entity.ServiceArticle;
import org.apache.ibatis.annotations.Mapper;

/**
 * 气象服务内容 Mapper
 */
@Mapper
public interface ServiceArticleMapper extends BaseMapper<ServiceArticle> {
}