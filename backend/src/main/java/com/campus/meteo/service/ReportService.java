package com.campus.meteo.service;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.ReportGenerateReq;
import com.campus.meteo.entity.ReportFile;

/**
 * 统计报表服务
 */
public interface ReportService {

    /** 报表文件分页（period 支持 yyyy-MM 或 yyyy） */
    PageResult<ReportFile> page(long pageNum, long pageSize, Long stationId, Integer reportType, String period);

    /** 手动生成报表，返回报表记录ID */
    Long generate(ReportGenerateReq req);

    /** 读取报表文件内容 */
    ReportDownload download(Long id);

    /** 报表下载载荷：文件名 + 文件内容 */
    record ReportDownload(String fileName, byte[] content) {
    }
}