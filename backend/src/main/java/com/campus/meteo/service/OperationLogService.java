package com.campus.meteo.service;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.entity.OperationLog;

/**
 * 操作日志服务
 */
public interface OperationLogService {

    /** 记录一条操作日志（异常不阻断主流程） */
    void record(String module, String operation, String params);

    /** 操作日志分页 */
    PageResult<OperationLog> page(long pageNum, long pageSize, Long userId, String module,
                                  String startTime, String endTime);
}