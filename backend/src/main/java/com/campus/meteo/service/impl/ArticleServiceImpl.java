package com.campus.meteo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.ArticleSaveReq;
import com.campus.meteo.entity.ServiceArticle;
import com.campus.meteo.mapper.ServiceArticleMapper;
import com.campus.meteo.service.ArticleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 气象服务内容实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {

    private final ServiceArticleMapper serviceArticleMapper;

    @Override
    public PageResult<ServiceArticle> page(long pageNum, long pageSize, Integer category, Integer publishStatus) {
        Page<ServiceArticle> page = serviceArticleMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<ServiceArticle>()
                        // 列表接口不返回 content 大字段，避免拖累查询
                        .select(ServiceArticle.class, info -> !"content".equals(info.getColumn()))
                        .eq(category != null, ServiceArticle::getCategory, category)
                        .eq(publishStatus != null, ServiceArticle::getPublishStatus, publishStatus)
                        .orderByDesc(ServiceArticle::getId));
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Override
    public ServiceArticle detail(Long id) {
        ServiceArticle article = serviceArticleMapper.selectById(id);
        if (article == null || article.getPublishStatus() == null || article.getPublishStatus() != 1) {
            throw new BizException(ErrorCode.NOT_FOUND, "服务内容不存在或未发布");
        }
        return article;
    }

    @Override
    public void create(ArticleSaveReq req) {
        ServiceArticle article = new ServiceArticle();
        copy(req, article);
        article.setPublishStatus(0);
        serviceArticleMapper.insert(article);
    }

    @Override
    public void update(Long id, ArticleSaveReq req) {
        if (serviceArticleMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "服务内容不存在");
        }
        ServiceArticle article = new ServiceArticle();
        copy(req, article);
        article.setId(id);
        serviceArticleMapper.updateById(article);
    }

    @Override
    public void changePublishStatus(Long id, Integer publishStatus) {
        if (publishStatus == null || (publishStatus != 1 && publishStatus != 2)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "上下架状态仅支持 1已发布 / 2下架");
        }
        if (serviceArticleMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "服务内容不存在");
        }
        ServiceArticle update = new ServiceArticle();
        update.setId(id);
        update.setPublishStatus(publishStatus);
        if (publishStatus == 1) {
            update.setPublishTime(LocalDateTime.now());
        }
        serviceArticleMapper.updateById(update);
    }

    private void copy(ArticleSaveReq req, ServiceArticle article) {
        article.setCategory(req.getCategory());
        article.setTitle(req.getTitle());
        article.setContent(req.getContent());
        article.setStationId(req.getStationId());
    }
}