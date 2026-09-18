package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 气象服务内容实体（农业气象/旅游气象/出行指数/科普）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("service_article")
public class ServiceArticle extends BaseEntity {

    /** 分类：1农业气象 2旅游气象 3出行指数 4科普 */
    private Integer category;

    /** 标题 */
    private String title;

    /** 富文本内容 */
    private String content;

    /** 关联站点（可空） */
    private Long stationId;

    /** 状态：0草稿 1已发布 2下架 */
    private Integer publishStatus;

    /** 发布时间 */
    private LocalDateTime publishTime;
}