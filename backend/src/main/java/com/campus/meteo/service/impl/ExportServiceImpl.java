package com.campus.meteo.service.impl;

import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.dto.ExportTaskResp;
import com.campus.meteo.dto.HistoryResp;
import com.campus.meteo.dto.SeriesPoint;
import com.campus.meteo.security.SecurityUtils;
import com.campus.meteo.service.DataQueryService;
import com.campus.meteo.service.ExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 历史数据异步导出实现
 *
 * 任务状态存于 Redis（TTL 1 小时），避免为短时任务单独建表；
 * 幂等由 clientToken 保证，频次由 Redis 计数限流（5 次/分钟/用户）。
 */
@Slf4j
@Service
public class ExportServiceImpl implements ExportService {

    private static final String KEY_TASK = "meteo:export:task:";
    private static final String KEY_LIMIT = "meteo:export:limit:";
    private static final String KEY_TOKEN = "meteo:export:token:";

    /** 单用户每分钟最多提交次数（见 API 规范 4.4 限流约定） */
    private static final int MAX_PER_MINUTE = 5;

    private static final Duration TASK_TTL = Duration.ofHours(1);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 支持的导出格式（excel 生成 xlsx，见 toXlsx） */
    private static final Set<String> SUPPORTED_FORMATS = Set.of("csv", "txt", "excel");

    /** 导出文件名后缀：excel 对应 xlsx */
    private static final String EXT_EXCEL = "xlsx";

    /**
     * xlsx 写入时驻留内存的行数，超出部分落临时文件。
     * 导出固定按分钟粒度取数，数天的数据可达数十万行，全量驻留内存会把堆撑爆，
     * 因此用 SXSSF 流式写出。
     */
    private static final int XLSX_ROW_WINDOW = 500;

    private final DataQueryService dataQueryService;
    private final StringRedisTemplate redisTemplate;
    private final TaskExecutor taskExecutor;

    /**
     * 显式构造而非 @RequiredArgsConstructor：Spring Boot 3.5 起容器中同时存在
     * applicationTaskExecutor 与 taskScheduler 两个 TaskExecutor 候选（后者是
     * ThreadPoolTaskScheduler，同样实现了 AsyncTaskExecutor），按类型注入会产生歧义。
     * 这里显式指定应用级线程池，与升级前的行为一致（导出为长任务，但历史行为如此，不改变）。
     * 注：Lombok 默认不会把字段上的 @Qualifier 复制到生成的构造参数，故必须手写构造器。
     */
    public ExportServiceImpl(DataQueryService dataQueryService,
                             StringRedisTemplate redisTemplate,
                             @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.dataQueryService = dataQueryService;
        this.redisTemplate = redisTemplate;
        this.taskExecutor = taskExecutor;
    }

    @Value("${meteo.export.dir:./exports}")
    private String exportDir;

    @Override
    public String submit(Long stationId, String elements, String startTime, String endTime,
                         String format, String clientToken) {
        String fmt = StringUtils.hasText(format) ? format.toLowerCase() : "csv";
        if (!SUPPORTED_FORMATS.contains(fmt)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "暂不支持的导出格式: " + fmt + "，当前支持 csv/txt/excel");
        }

        Long userId = SecurityUtils.getCurrentUserId();

        // 幂等：同一 clientToken 直接复用已有任务
        String tokenKey = KEY_TOKEN + userId + ":" + clientToken;
        if (StringUtils.hasText(clientToken)) {
            String existed = redisTemplate.opsForValue().get(tokenKey);
            if (existed != null) {
                return existed;
            }
        }

        // 限流
        String limitKey = KEY_LIMIT + userId;
        Long count = redisTemplate.opsForValue().increment(limitKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(limitKey, Duration.ofMinutes(1));
        }
        if (count != null && count > MAX_PER_MINUTE) {
            throw new BizException(ErrorCode.EXPORT_TASK_BUSY);
        }

        String taskId = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> task = new HashMap<>();
        task.put("status", "RUNNING");
        task.put("createTime", LocalDateTime.now().format(TIME_FMT));
        redisTemplate.opsForHash().putAll(KEY_TASK + taskId, task);
        redisTemplate.expire(KEY_TASK + taskId, TASK_TTL);
        if (StringUtils.hasText(clientToken)) {
            redisTemplate.opsForValue().set(tokenKey, taskId, TASK_TTL);
        }

        taskExecutor.execute(() -> doExport(taskId, stationId, elements, startTime, endTime, fmt));
        log.info("导出任务已提交: taskId={}, stationId={}, format={}, userId={}", taskId, stationId, fmt, userId);
        return taskId;
    }

    @Override
    public ExportTaskResp status(String taskId) {
        Map<Object, Object> task = redisTemplate.opsForHash().entries(KEY_TASK + taskId);
        if (task.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "导出任务不存在或已过期");
        }
        ExportTaskResp resp = new ExportTaskResp();
        resp.setTaskId(taskId);
        resp.setStatus((String) task.get("status"));
        resp.setFileName((String) task.get("fileName"));
        resp.setMessage((String) task.get("message"));
        resp.setCreateTime((String) task.get("createTime"));
        if ("SUCCESS".equals(resp.getStatus())) {
            resp.setDownloadUrl("/api/v1/export/tasks/" + taskId + "/download");
        }
        return resp;
    }

    @Override
    public DownloadFile download(String taskId) {
        Map<Object, Object> task = redisTemplate.opsForHash().entries(KEY_TASK + taskId);
        if (task.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "导出任务不存在或已过期");
        }
        if (!"SUCCESS".equals(task.get("status"))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "导出任务尚未完成");
        }
        Path file = Paths.get((String) task.get("filePath"));
        if (!Files.exists(file)) {
            throw new BizException(ErrorCode.NOT_FOUND, "导出文件不存在或已被清理");
        }
        try {
            return new DownloadFile((String) task.get("fileName"), Files.readAllBytes(file));
        } catch (IOException e) {
            log.error("导出文件读取失败: taskId={}, err={}", taskId, e.getMessage(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "导出文件读取失败");
        }
    }

    private void doExport(String taskId, Long stationId, String elements,
                          String startTime, String endTime, String format) {
        try {
            HistoryResp history = dataQueryService.history(stationId, elements, startTime, endTime, "min", null);
            byte[] content = switch (format) {
                case "excel" -> toXlsx(history);
                case "txt" -> toTxt(history).getBytes(StandardCharsets.UTF_8);
                default -> toCsv(history).getBytes(StandardCharsets.UTF_8);
            };
            String extension = "excel".equals(format) ? EXT_EXCEL : format;

            Path dir = Paths.get(exportDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve("export_%d_%s.%s".formatted(stationId, taskId, extension));
            Files.write(file, content);

            Map<String, String> update = new HashMap<>();
            update.put("status", "SUCCESS");
            update.put("fileName", file.getFileName().toString());
            update.put("filePath", file.toString());
            redisTemplate.opsForHash().putAll(KEY_TASK + taskId, update);
            log.info("导出任务完成: taskId={}, file={}", taskId, file);
        } catch (Exception e) {
            log.error("导出任务失败: taskId={}, err={}", taskId, e.getMessage(), e);
            Map<String, String> update = new HashMap<>();
            update.put("status", "FAILED");
            update.put("message", e.getMessage());
            redisTemplate.opsForHash().putAll(KEY_TASK + taskId, update);
        }
    }

    private String toCsv(HistoryResp history) {
        // 前置 BOM，便于 Excel 正确识别中文
        StringBuilder sb = new StringBuilder("\uFEFF要素,时间,数值,质控标记\n");
        history.getSeries().forEach((element, points) -> points.forEach(point ->
                sb.append(element).append(',')
                        .append(point.getTime()).append(',')
                        .append(point.getValue()).append(',')
                        .append(point.getQcFlag() == null ? "" : point.getQcFlag()).append('\n')));
        return sb.toString();
    }

    private String toTxt(HistoryResp history) {
        StringBuilder sb = new StringBuilder();
        sb.append("站点: ").append(history.getStationCode())
                .append("  粒度: ").append(history.getGranularity()).append("\n\n");
        history.getSeries().forEach((element, points) -> {
            sb.append("== ").append(element).append(" ==\n");
            for (SeriesPoint point : points) {
                sb.append(point.getTime()).append('\t').append(point.getValue())
                        .append('\t').append(point.getQcFlag() == null ? "" : point.getQcFlag()).append('\n');
            }
            sb.append('\n');
        });
        return sb.toString();
    }

    /**
     * 生成 xlsx（FR-DS-04）。
     *
     * 用 SXSSF 流式写出而非 XSSF：导出固定按分钟粒度取数，数天数据可达数十万行，
     * XSSF 会把全部行驻留内存。表头加粗、冻结首行、列宽固定，便于人工查看。
     * 列结构与 csv/txt 保持一致（要素 / 时间 / 数值 / 质控标记），要素名沿用原始键，
     * 避免与其它两种格式产生口径差异。
     */
    private byte[] toXlsx(HistoryResp history) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(XLSX_ROW_WINDOW)) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet("历史数据");

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            String[] titles = {"要素", "时间", "数值", "质控标记"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < titles.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(titles[i]);
                cell.setCellStyle(headerStyle);
            }
            sheet.createFreezePane(0, 1);
            sheet.setColumnWidth(0, 12 * 256);
            sheet.setColumnWidth(1, 22 * 256);
            sheet.setColumnWidth(2, 12 * 256);
            sheet.setColumnWidth(3, 12 * 256);

            int rowIndex = 1;
            for (Map.Entry<String, List<SeriesPoint>> entry : history.getSeries().entrySet()) {
                for (SeriesPoint point : entry.getValue()) {
                    Row row = sheet.createRow(rowIndex++);
                    row.createCell(0).setCellValue(entry.getKey());
                    row.createCell(1).setCellValue(point.getTime());
                    Cell valueCell = row.createCell(2);
                    if (point.getValue() == null) {
                        valueCell.setBlank();
                    } else {
                        valueCell.setCellValue(point.getValue());
                    }
                    row.createCell(3).setCellValue(point.getQcFlag() == null ? "" : point.getQcFlag());
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            // 显式清理 SXSSF 落盘的临时文件（close 亦会调用，此处确保异常路径下也回收）
            workbook.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            log.error("xlsx 生成失败: station={}, err={}", history.getStationCode(), e.getMessage(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "Excel 文件生成失败");
        }
    }
}