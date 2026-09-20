package com.campus.meteo.service.impl;

import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.dto.ExportTaskResp;
import com.campus.meteo.dto.HistoryResp;
import com.campus.meteo.dto.SeriesPoint;
import com.campus.meteo.security.LoginUser;
import com.campus.meteo.service.DataQueryService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 历史数据异步导出单测：验证 excel 格式产出的确是合法 xlsx，且三种格式口径一致。
 *
 * 导出在异步线程里执行，这里让 TaskExecutor 同步跑，便于断言文件与状态。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("历史数据导出")
class ExportServiceImplTest {

    private static final Long STATION_ID = 1L;

    @Mock
    private DataQueryService dataQueryService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    /** 同步执行的 TaskExecutor：直接在当前线程跑任务，便于断言 */
    private final TaskExecutor syncExecutor = Runnable::run;

    private ExportServiceImpl service;
    private Path exportDir;

    @BeforeEach
    void setUp() throws Exception {
        exportDir = Files.createTempDirectory("meteo-export-test");
        service = new ExportServiceImpl(dataQueryService, redisTemplate, syncExecutor);
        ReflectionTestUtils.setField(service, "exportDir", exportDir.toString());

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(valueOperations.increment(anyString())).thenReturn(1L);

        LoginUser user = new LoginUser(7L, "admin", "x", "管理员", true,
                List.of("ADMIN"), List.of("data:export"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("excel 格式 → 生成合法 xlsx，表头与数据行正确")
    void shouldGenerateValidXlsx() throws Exception {
        when(dataQueryService.history(eq(STATION_ID), any(), any(), any(), eq("min"), any()))
                .thenReturn(history());

        service.submit(STATION_ID, "temp,rain", null, null, "excel", null);

        // 任务状态应为成功，文件名为 xlsx 后缀
        Map<Object, Object> saved = captureTaskState();
        assertThat(saved.get("status")).isEqualTo("SUCCESS");
        assertThat((String) saved.get("fileName")).endsWith(".xlsx");

        Path file = exportDir.resolve((String) saved.get("fileName"));
        assertThat(file).exists();

        // 用 POI 反向读回，确认是合法 xlsx 且内容正确
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(Files.readAllBytes(file)))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("要素");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("时间");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("数值");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("质控标记");
            assertThat(header.getCell(0).getCellStyle().getFontIndexAsInt()).isNotZero();

            // 表头 1 行 + 数据 3 行（temp 2 条、rain 1 条），末行索引为 3
            assertThat(sheet.getLastRowNum()).isEqualTo(3);
            Row first = sheet.getRow(1);
            assertThat(first.getCell(0).getStringCellValue()).isEqualTo("temp");
            assertThat(first.getCell(1).getStringCellValue()).isEqualTo("2026-09-20 10:00:00");
            assertThat(first.getCell(2).getNumericCellValue()).isEqualTo(23.5);
            assertThat(first.getCell(3).getStringCellValue()).isEqualTo("passed");
            // 第二个要素的行紧接其后，确认两个要素都被写出
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("rain");
        }
    }

    @Test
    @DisplayName("xlsx 数值列为空时不写成 0，保持空白")
    void shouldLeaveBlankWhenValueMissing() throws Exception {
        HistoryResp resp = history();
        resp.getSeries().get("temp").get(0).setValue(null);
        when(dataQueryService.history(eq(STATION_ID), any(), any(), any(), eq("min"), any()))
                .thenReturn(resp);

        service.submit(STATION_ID, "temp", null, null, "excel", null);

        Path file = exportDir.resolve((String) captureTaskState().get("fileName"));
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(Files.readAllBytes(file)))) {
            var cell = workbook.getSheetAt(0).getRow(1).getCell(2);
            assertThat(cell.getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
        }
    }

    @Test
    @DisplayName("csv 格式仍按原口径输出，带 BOM")
    void shouldStillProduceCsv() throws Exception {
        when(dataQueryService.history(eq(STATION_ID), any(), any(), any(), eq("min"), any()))
                .thenReturn(history());

        service.submit(STATION_ID, "temp", null, null, "csv", null);

        Map<Object, Object> saved = captureTaskState();
        assertThat((String) saved.get("fileName")).endsWith(".csv");
        String content = Files.readString(exportDir.resolve((String) saved.get("fileName")), StandardCharsets.UTF_8);
        assertThat(content).startsWith("\uFEFF");
        assertThat(content).contains("要素,时间,数值,质控标记");
        assertThat(content).contains("temp,2026-09-20 10:00:00,23.5,passed");
    }

    @Test
    @DisplayName("txt 格式仍可导出")
    void shouldStillProduceTxt() throws Exception {
        when(dataQueryService.history(eq(STATION_ID), any(), any(), any(), eq("min"), any()))
                .thenReturn(history());

        service.submit(STATION_ID, "temp", null, null, "txt", null);

        Map<Object, Object> saved = captureTaskState();
        assertThat((String) saved.get("fileName")).endsWith(".txt");
        String content = Files.readString(exportDir.resolve((String) saved.get("fileName")), StandardCharsets.UTF_8);
        assertThat(content).contains("== temp ==");
    }

    @Test
    @DisplayName("格式大小写不敏感，EXCEL 亦可")
    void shouldAcceptUpperCaseFormat() {
        when(dataQueryService.history(eq(STATION_ID), any(), any(), any(), eq("min"), any()))
                .thenReturn(history());

        service.submit(STATION_ID, "temp", null, null, "EXCEL", null);

        assertThat((String) captureTaskState().get("fileName")).endsWith(".xlsx");
    }

    @Test
    @DisplayName("不支持的格式 → 参数错误，不创建任务")
    void shouldRejectUnsupportedFormat() {
        assertThatThrownBy(() -> service.submit(STATION_ID, "temp", null, null, "pdf", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("暂不支持的导出格式");
        verify(hashOperations, never()).putAll(anyString(), any());
    }

    @Test
    @DisplayName("生成失败 → 任务状态写为 FAILED 并带错误信息，不向上抛")
    void shouldMarkFailedWhenQueryThrows() {
        when(dataQueryService.history(eq(STATION_ID), any(), any(), any(), eq("min"), any()))
                .thenThrow(new IllegalStateException("时序库不可用"));

        service.submit(STATION_ID, "temp", null, null, "excel", null);

        Map<Object, Object> saved = captureTaskState();
        assertThat(saved.get("status")).isEqualTo("FAILED");
        assertThat((String) saved.get("message")).contains("时序库不可用");
    }

    @Test
    @DisplayName("clientToken 命中已有任务 → 直接复用，不再生成文件")
    void shouldReuseTaskWhenClientTokenExists() {
        when(valueOperations.get(anyString())).thenReturn("existing-task-id");

        String taskId = service.submit(STATION_ID, "temp", null, null, "excel", "tok-1");

        assertThat(taskId).isEqualTo("existing-task-id");
        verify(dataQueryService, never()).history(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("提交频次超限 → 抛出任务繁忙")
    void shouldRejectWhenRateLimited() {
        when(valueOperations.increment(anyString())).thenReturn(99L);

        assertThatThrownBy(() -> service.submit(STATION_ID, "temp", null, null, "csv", null))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("任务不存在 → 查询状态与下载均报未找到")
    void shouldReportNotFoundForUnknownTask() {
        when(hashOperations.entries(anyString())).thenReturn(Map.of());

        assertThatThrownBy(() -> service.status("nope")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.download("nope")).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("任务未完成 → 拒绝下载")
    void shouldRejectDownloadWhenNotFinished() {
        when(hashOperations.entries(anyString()))
                .thenReturn(Map.of("status", "RUNNING"));

        assertThatThrownBy(() -> service.download("t1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未完成");
    }

    @Test
    @DisplayName("状态查询：成功后附带下载地址")
    void shouldExposeDownloadUrlWhenSucceeded() {
        Map<Object, Object> task = new HashMap<>();
        task.put("status", "SUCCESS");
        task.put("fileName", "export_1_abc.xlsx");
        task.put("createTime", "2026-09-20 10:00:00");
        when(hashOperations.entries(anyString())).thenReturn(task);

        ExportTaskResp resp = service.status("abc");

        assertThat(resp.getStatus()).isEqualTo("SUCCESS");
        assertThat(resp.getDownloadUrl()).isEqualTo("/api/v1/export/tasks/abc/download");
    }

    /** 抓取任务状态写入（doExport 完成时的那次 putAll） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<Object, Object> captureTaskState() {
        var captor = org.mockito.ArgumentCaptor.forClass((Class) Map.class);
        verify(hashOperations, org.mockito.Mockito.atLeastOnce())
                .putAll(anyString(), (Map) captor.capture());
        List<Map<Object, Object>> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    /** 构造一条含两个要素、共 3 个点的历史数据 */
    private HistoryResp history() {
        HistoryResp resp = new HistoryResp();
        resp.setStationId(STATION_ID);
        resp.setStationCode("CAMPUS01");
        resp.setGranularity("min");

        Map<String, List<SeriesPoint>> series = new LinkedHashMap<>();
        series.put("temp", new ArrayList<>(List.of(
                point("2026-09-20 10:00:00", 23.5, "passed"),
                point("2026-09-20 10:01:00", 23.7, "interpolated"))));
        series.put("rain", new ArrayList<>(List.of(
                point("2026-09-20 10:00:00", 0.0, "passed"))));
        resp.setSeries(series);
        return resp;
    }

    private SeriesPoint point(String time, Double value, String qcFlag) {
        SeriesPoint p = new SeriesPoint();
        p.setTime(time);
        p.setValue(value);
        p.setQcFlag(qcFlag);
        return p;
    }
}