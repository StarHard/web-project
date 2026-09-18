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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
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
@RequiredArgsConstructor
public class ExportServiceImpl implements ExportService {

    private static final String KEY_TASK = "meteo:export:task:";
    private static final String KEY_LIMIT = "meteo:export:limit:";
    private static final String KEY_TOKEN = "meteo:export:token:";

    /** 单用户每分钟最多提交次数（见 API 规范 4.4 限流约定） */
    private static final int MAX_PER_MINUTE = 5;

    private static final Duration TASK_TTL = Duration.ofHours(1);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 暂支持的导出格式（excel 需引入 POI 依赖，待评估后再开放） */
    private static final Set<String> SUPPORTED_FORMATS = Set.of("csv", "txt");

    private final DataQueryService dataQueryService;
    private final StringRedisTemplate redisTemplate;
    private final TaskExecutor taskExecutor;

    @Value("${meteo.export.dir:./exports}")
    private String exportDir;

    @Override
    public String submit(Long stationId, String elements, String startTime, String endTime,
                         String format, String clientToken) {
        String fmt = StringUtils.hasText(format) ? format.toLowerCase() : "csv";
        if (!SUPPORTED_FORMATS.contains(fmt)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "暂不支持的导出格式: " + fmt + "，当前支持 csv/txt");
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
            String content = "csv".equals(format) ? toCsv(history) : toTxt(history);

            Path dir = Paths.get(exportDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve("export_%d_%s.%s".formatted(stationId, taskId, format));
            Files.writeString(file, content, StandardCharsets.UTF_8);

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
}