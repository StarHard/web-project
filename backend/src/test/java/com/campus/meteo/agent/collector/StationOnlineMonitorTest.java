package com.campus.meteo.agent.collector;

import com.campus.meteo.entity.Station;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站点离线检测单测：验证阈值来源、条件更新与单站失败隔离。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("站点离线检测")
class StationOnlineMonitorTest {

    @Mock
    private StationMapper stationMapper;
    @Mock
    private SysConfigService sysConfigService;

    private StationOnlineMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new StationOnlineMonitor(stationMapper, sysConfigService);
    }

    @Test
    @DisplayName("超过阈值的在线站点 → 条件更新把 online_flag 置 0")
    void shouldMarkStaleStationOffline() {
        when(sysConfigService.getInt("station.offline.minutes", 10)).thenReturn(10);
        when(stationMapper.selectList(any())).thenReturn(List.of(staleStation("CAMPUS01", 1L, 30)));
        when(stationMapper.update(any(), any())).thenReturn(1);

        int marked = monitor.checkOffline();

        assertThat(marked).isEqualTo(1);
        // 更新实体只带待改字段，条件（id / online_flag / last_report_time）由 wrapper 承载
        ArgumentCaptor<Station> captor = ArgumentCaptor.forClass(Station.class);
        verify(stationMapper).update(captor.capture(), any());
        assertThat(captor.getValue().getOnlineFlag()).isZero();
        assertThat(captor.getValue().getStationCode()).isNull();
    }

    @Test
    @DisplayName("条件更新影响 0 行（期间已有新上报恢复在线）→ 不计入离线数")
    void shouldNotCountWhenConditionalUpdateAffectsNoRow() {
        when(sysConfigService.getInt("station.offline.minutes", 10)).thenReturn(10);
        when(stationMapper.selectList(any())).thenReturn(List.of(staleStation("CAMPUS01", 1L, 30)));
        when(stationMapper.update(any(), any())).thenReturn(0);

        assertThat(monitor.checkOffline()).isZero();
    }

    @Test
    @DisplayName("没有超阈值站点 → 不做任何更新")
    void shouldDoNothingWhenNoStaleStation() {
        when(sysConfigService.getInt("station.offline.minutes", 10)).thenReturn(10);
        when(stationMapper.selectList(any())).thenReturn(List.of());

        assertThat(monitor.checkOffline()).isZero();
        verify(stationMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("阈值取自 sys_config：改成 0 分钟后判定立即生效")
    void shouldUseThresholdFromSysConfig() {
        when(sysConfigService.getInt("station.offline.minutes", 10)).thenReturn(0);
        when(stationMapper.selectList(any()))
                .thenReturn(List.of(staleStation("FARM02", 2L, 1)));
        when(stationMapper.update(any(), any())).thenReturn(1);

        assertThat(monitor.checkOffline()).isEqualTo(1);
        verify(sysConfigService).getInt("station.offline.minutes", 10);
    }

    @Test
    @DisplayName("单站标记失败不影响其余站点")
    void shouldContinueWhenOneStationFails() {
        when(sysConfigService.getInt("station.offline.minutes", 10)).thenReturn(10);
        when(stationMapper.selectList(any())).thenReturn(List.of(
                staleStation("BAD01", 1L, 30), staleStation("CAMPUS01", 2L, 30)));
        when(stationMapper.update(any(), any()))
                .thenThrow(new IllegalStateException("数据库异常"))
                .thenReturn(1);

        assertThat(monitor.checkOffline()).isEqualTo(1);
    }

    private Station staleStation(String code, Long id, int minutesSinceLastReport) {
        Station station = new Station();
        station.setId(id);
        station.setStationCode(code);
        station.setStatus(1);
        station.setOnlineFlag(1);
        station.setLastReportTime(LocalDateTime.now().minusMinutes(minutesSinceLastReport));
        return station;
    }
}