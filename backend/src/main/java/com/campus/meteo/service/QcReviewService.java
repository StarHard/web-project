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
}