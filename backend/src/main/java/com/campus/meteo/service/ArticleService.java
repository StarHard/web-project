package com.campus.meteo.service;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.ArticleSaveReq;
import com.campus.meteo.entity.ServiceArticle;

/**
 * 气象服务内容服务
 */
public interface ArticleService {

    /** 内容分页（列表不返回 content 大字段） */
    PageResult<ServiceArticle> page(long pageNum, long pageSize, Integer category, Integer publishStatus);

    /** 内容详情（仅已发布内容对公开接口可见） */
    ServiceArticle detail(Long id);

    void create(ArticleSaveReq req);

    void update(Long id, ArticleSaveReq req);

    /** 上架/下架：publishStatus 1已发布 2下架 */
    void changePublishStatus(Long id, Integer publishStatus);
}