package com.campus.meteo.service;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.QcReviewReq;
import com.campus.meteo.entity.QcReviewTask;

/**
 * 质控人工审核服务
 */
public interface QcReviewService {

    /** 审核任务分页 */
    PageResult<QcReviewTask> page(long pageNum, long pageSize, Long stationId, Integer status,
                                  String startTime, String endTime);

    /** 审核：确认有效 / 修正 / 作废，结果回写时序库形成闭环 */
    void review(Long id, QcReviewReq req);

    /**
     * 手动触发一轮缺测插补（FR-QC-05）
     *
     * 定时任务扫描周期较长，本接口供运维与验收当场复现一轮插补。
     */
    InterpolationSummary interpolateMissing();

    /** 缺测插补本轮统计 */
    record InterpolationSummary(int stationsScanned, int pointsWritten,
                                int gapsSkipped, boolean skippedByLock) {
    }
}