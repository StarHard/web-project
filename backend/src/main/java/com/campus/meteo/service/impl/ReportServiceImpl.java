package com.campus.meteo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.ReportGenerateReq;
import com.campus.meteo.dto.StatsResp;
import com.campus.meteo.entity.ReportFile;
import com.campus.meteo.mapper.ReportFileMapper;
import com.campus.meteo.security.SecurityUtils;
import com.campus.meteo.service.DataQueryService;
import com.campus.meteo.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 统计报表实现
 *
 * 复用 DataQueryService 的统计能力生成 CSV 报表，文件落盘后登记 report_file。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReportFileMapper reportFileMapper;
    private final DataQueryService dataQueryService;

    /** 报表文件存储目录（可用 meteo.report.dir 覆盖） */
    @Value("${meteo.report.dir:./reports}")
    private String reportDir;

    @Override
    public PageResult<ReportFile> page(long pageNum, long pageSize, Long stationId, Integer reportType, String period) {
        LambdaQueryWrapper<ReportFile> wrapper = new LambdaQueryWrapper<ReportFile>()
                .eq(stationId != null, ReportFile::getStationId, stationId)
                .eq(reportType != null, ReportFile::getReportType, reportType)
                .orderByDesc(ReportFile::getId);
        applyPeriod(wrapper, period);
        Page<ReportFile> page = reportFileMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Override
    public Long generate(ReportGenerateReq req) {
        Integer type = req.getReportType();
        LocalDate start = req.getPeriodStart();
        LocalDate end = req.getPeriodEnd() != null ? req.getPeriodEnd() : start;

        StatsResp stats = switch (type) {
            case 1 -> dataQueryService.daily(req.getStationId(), start.toString());
            case 2 -> dataQueryService.monthly(req.getStationId(), YearMonth.from(start).toString());
            case 3 -> dataQueryService.yearly(req.getStationId(), String.valueOf(start.getYear()));
            case 4 -> dataQueryService.extreme(req.getStationId(), req.getElement(),
                    start.atStartOfDay().format(TIME_FMT), end.plusDays(1).atStartOfDay().format(TIME_FMT));
            case 5 -> dataQueryService.climate(req.getStationId(), start.getMonthValue(), req.getElement());
            default -> throw new BizException(ErrorCode.PARAM_ERROR, "不支持的报表类型: " + type);
        };

        Path file = writeCsv(type, req.getStationId(), start, toCsv(stats));

        ReportFile record = new ReportFile();
        record.setStationId(req.getStationId());
        record.setReportType(type);
        record.setPeriodStart(start);
        record.setPeriodEnd(end);
        record.setFilePath(file.toString());
        record.setCreateBy(SecurityUtils.getCurrentUserId());
        reportFileMapper.insert(record);
        log.info("报表生成完成: id={}, type={}, station={}", record.getId(), type, req.getStationId());
        return record.getId();
    }

    @Override
    public ReportDownload download(Long id) {
        ReportFile record = reportFileMapper.selectById(id);
        if (record == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报表记录不存在");
        }
        Path file = Paths.get(record.getFilePath());
        if (!Files.exists(file)) {
            throw new BizException(ErrorCode.NOT_FOUND, "报表文件不存在或已被清理");
        }
        try {
            return new ReportDownload(file.getFileName().toString(), Files.readAllBytes(file));
        } catch (IOException e) {
            log.error("报表文件读取失败: id={}, path={}, err={}", id, record.getFilePath(), e.getMessage(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "报表文件读取失败");
        }
    }

    private void applyPeriod(LambdaQueryWrapper<ReportFile> wrapper, String period) {
        if (!StringUtils.hasText(period)) {
            return;
        }
        try {
            if (period.length() == 7) {
                YearMonth ym = YearMonth.parse(period);
                wrapper.ge(ReportFile::getPeriodStart, ym.atDay(1))
                        .le(ReportFile::getPeriodStart, ym.atEndOfMonth());
            } else if (period.length() == 4) {
                int year = Integer.parseInt(period);
                wrapper.ge(ReportFile::getPeriodStart, LocalDate.of(year, 1, 1))
                        .le(ReportFile::getPeriodStart, LocalDate.of(year, 12, 31));
            } else {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "period 格式非法，应为 yyyy-MM 或 yyyy");
        }
    }

    private Path writeCsv(Integer type, Long stationId, LocalDate start, String content) {
        Path dir = Paths.get(reportDir).toAbsolutePath().normalize();
        Path file = dir.resolve("report_%d_%d_%s.csv".formatted(type, stationId, start));
        try {
            Files.createDirectories(dir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            log.error("报表文件写入失败: path={}, err={}", file, e.getMessage(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "报表文件写入失败");
        }
    }

    /** 生成 CSV 文本，前置 BOM 便于 Excel 正确识别中文 */
    private String toCsv(StatsResp stats) {
        StringBuilder sb = new StringBuilder("\uFEFF");
        if (stats.getClimateSeries() != null && !stats.getClimateSeries().isEmpty()) {
            sb.append("年份,平均值,样本数\n");
            for (Map<String, Object> item : stats.getClimateSeries()) {
                sb.append(item.get("year")).append(',')
                        .append(item.get("avg")).append(',')
                        .append(item.get("count")).append('\n');
            }
            return sb.toString();
        }
        sb.append("要素,样本数,平均值,最大值,最大值时间,最小值,最小值时间,累计值\n");
        stats.getStats().forEach((element, stat) -> sb.append(element).append(',')
                .append(stat.getCount()).append(',')
                .append(stat.getAvg()).append(',')
                .append(stat.getMax()).append(',')
                .append(stat.getMaxTime() == null ? "" : stat.getMaxTime()).append(',')
                .append(stat.getMin()).append(',')
                .append(stat.getMinTime() == null ? "" : stat.getMinTime()).append(',')
                .append(stat.getSum()).append('\n'));
        return sb.toString();
    }
}