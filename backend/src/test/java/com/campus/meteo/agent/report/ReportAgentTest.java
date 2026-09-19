package com.campus.meteo.agent.report;

import com.campus.meteo.dto.ReportGenerateReq;
import com.campus.meteo.entity.Station;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报表 Agent 定时生成逻辑单测：验证周期口径与单站失败的隔离性。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("报表 Agent 定时生成逻辑")
class ReportAgentTest {

    @Mock
    private StationMapper stationMapper;
    @Mock
    private ReportService reportService;

    private ReportAgent reportAgent;

    @BeforeEach
    void setUp() {
        reportAgent = new ReportAgent(stationMapper, reportService);
    }

    @Test
    @DisplayName("日报：为每个正常站点生成一份前一日日报")
    void shouldGenerateDailyReportForEveryEnabledStation() {
        stubStations(station(1L, "CAMPUS01"), station(2L, "FARM02"));

        reportAgent.generateDailyReports();

        List<ReportGenerateReq> requests = captureRequests(2);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        assertThat(requests).allSatisfy(req -> {
            assertThat(req.getReportType()).isEqualTo(1);
            assertThat(req.getPeriodStart()).isEqualTo(yesterday);
            assertThat(req.getPeriodEnd()).isEqualTo(yesterday);
        });
        assertThat(requests).extracting(ReportGenerateReq::getStationId)
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("月报：周期取上一自然月的首尾日")
    void shouldGenerateMonthlyReportForPreviousMonth() {
        stubStations(station(1L, "CAMPUS01"));

        reportAgent.generateMonthlyReports();

        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        ReportGenerateReq req = captureRequests(1).get(0);
        assertThat(req.getReportType()).isEqualTo(2);
        assertThat(req.getPeriodStart()).isEqualTo(lastMonth.atDay(1));
        assertThat(req.getPeriodEnd()).isEqualTo(lastMonth.atEndOfMonth());
    }

    @Test
    @DisplayName("年报：周期取上一自然年的 1 月 1 日至 12 月 31 日")
    void shouldGenerateYearlyReportForPreviousYear() {
        stubStations(station(1L, "CAMPUS01"));

        reportAgent.generateYearlyReports();

        int lastYear = LocalDate.now().getYear() - 1;
        ReportGenerateReq req = captureRequests(1).get(0);
        assertThat(req.getReportType()).isEqualTo(3);
        assertThat(req.getPeriodStart()).isEqualTo(LocalDate.of(lastYear, 1, 1));
        assertThat(req.getPeriodEnd()).isEqualTo(LocalDate.of(lastYear, 12, 31));
    }

    @Test
    @DisplayName("单站生成失败不影响其余站点")
    void shouldContinueWhenOneStationFails() {
        stubStations(station(1L, "BAD01"), station(2L, "CAMPUS01"));
        when(reportService.generate(any(ReportGenerateReq.class), isNull()))
                .thenThrow(new IllegalStateException("聚合查询失败"))
                .thenReturn(100L);

        reportAgent.generateDailyReports();

        verify(reportService, org.mockito.Mockito.times(2))
                .generate(any(ReportGenerateReq.class), isNull());
    }

    private void stubStations(Station... stations) {
        when(stationMapper.selectList(any())).thenReturn(List.of(stations));
    }

    private List<ReportGenerateReq> captureRequests(int expectedCount) {
        ArgumentCaptor<ReportGenerateReq> captor = ArgumentCaptor.forClass(ReportGenerateReq.class);
        verify(reportService, org.mockito.Mockito.times(expectedCount))
                .generate(captor.capture(), isNull());
        return captor.getAllValues();
    }

    private Station station(Long id, String code) {
        Station station = new Station();
        station.setId(id);
        station.setStationCode(code);
        station.setStatus(1);
        return station;
    }
}