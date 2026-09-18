package com.campus.meteo.service;

import com.campus.meteo.dto.ExportTaskResp;

/**
 * 历史数据异步导出服务
 */
public interface ExportService {

    /**
     * 提交导出任务
     *
     * @param clientToken 防重复提交令牌（同用户同令牌复用同一任务）
     * @return 任务ID
     */
    String submit(Long stationId, String elements, String startTime, String endTime,
                  String format, String clientToken);

    /** 查询任务状态（含下载地址） */
    ExportTaskResp status(String taskId);

    /** 读取导出文件 */
    DownloadFile download(String taskId);

    /** 下载载荷：文件名 + 内容 */
    record DownloadFile(String fileName, byte[] content) {
    }
}