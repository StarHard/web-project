package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质控人工审核任务实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("qc_review_task")
public class QcReviewTask extends BaseEntity {

    /** 站点ID */
    private Long stationId;

    /** 要素 */
    private String element;

    /** 观测时间 */
    private LocalDateTime obsTime;

    /** 原始值 */
    private BigDecimal obsValue;

    /** 检验类型：1极值 2时间一致性 3空间一致性 */
    private Integer qcType;

    /** 检验详情 */
    private String qcDetail;

    /** 状态：0待审核 1确认有效 2修正 3作废 */
    private Integer status;

    /** 修正后的值 */
    private BigDecimal reviewedValue;

    /** 审核人 */
    private Long reviewerId;

    /** 审核时间 */
    private LocalDateTime reviewTime;
}
