package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 统计报表文件实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("report_file")
public class ReportFile extends BaseEntity {

    /** 站点ID（0=多站点汇总） */
    private Long stationId;

    /** 类型：1日报 2月报 3年报 4极值 5气候对比 */
    private Integer reportType;

    /** 统计起始日 */
    private LocalDate periodStart;

    /** 统计结束日 */
    private LocalDate periodEnd;

    /** 生成文件路径 */
    private String filePath;

    /** 触发者（NULL=定时Agent） */
    private Long createBy;
}