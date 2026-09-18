package com.campus.meteo.controller;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.ArticleSaveReq;
import com.campus.meteo.entity.ServiceArticle;
import com.campus.meteo.service.ArticleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 气象服务内容接口（查询公开，维护需 article:manage）
 */
@Tag(name = "气象服务内容")
@RestController
@RequestMapping("/articles")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;

    @Operation(summary = "服务内容分页（公开，默认仅已发布）")
    @GetMapping
    public Result<PageResult<ServiceArticle>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                   @RequestParam(defaultValue = "20") long pageSize,
                                                   @RequestParam(required = false) Integer category,
                                                   @RequestParam(required = false) Integer publishStatus) {
        return Result.ok(articleService.page(pageNum, pageSize, category, publishStatus));
    }

    @Operation(summary = "服务内容详情（公开）")
    @GetMapping("/{id}")
    public Result<ServiceArticle> detail(@PathVariable Long id) {
        return Result.ok(articleService.detail(id));
    }

    @Operation(summary = "新增服务内容")
    @PostMapping
    @PreAuthorize("hasAuthority('article:manage')")
    public Result<Void> create(@Valid @RequestBody ArticleSaveReq req) {
        articleService.create(req);
        return Result.ok();
    }

    @Operation(summary = "修改服务内容")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('article:manage')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ArticleSaveReq req) {
        articleService.update(id, req);
        return Result.ok();
    }

    @Operation(summary = "上架/下架：publishStatus 1已发布 2下架")
    @PatchMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('article:manage')")
    public Result<Void> changePublishStatus(@PathVariable Long id, @RequestParam Integer publishStatus) {
        articleService.changePublishStatus(id, publishStatus);
        return Result.ok();
    }
}