package com.campus.meteo.agent.collector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.meteo.entity.Station;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站点离线检测（FR-RT-05）：超过 N 分钟无数据上报则标记离线。
 *
 * 判定阈值读 sys_config 的 `station.offline.minutes`（该参数此前只建了值、从未被读取），
 * 因此运维在系统参数页改动能直接生效（SysConfigService 有 30 秒缓存）。
 *
 * 恢复不做处理：采集 Agent 收到任一上报即把 online_flag 置 1，无需本类兜底。
 *
 * 关于「通知」：站内可见性由 online_flag 驱动——站点地图按三态着色（正常/告警/离线）、
 * 仪表盘展示在线状态，标记后即时反映。短信/邮件/微信等外部通道依赖告警通道适配器，
 * 与 FR-AL-03 的短信/微信一致属尚未接入的部分，本类只落 WARN 日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StationOnlineMonitor {

    /** 离线判定阈值参数键（sys_config） */
    private static final String KEY_OFFLINE_MINUTES = "station.offline.minutes";
    /** 阈值缺省值（分钟） */
    private static final int DEFAULT_OFFLINE_MINUTES = 10;

    private final StationMapper stationMapper;
    private final SysConfigService sysConfigService;

    @Scheduled(fixedDelayString = "${meteo.station.offline-check-delay-ms:300000}", initialDelay = 60_000)
    public int checkOffline() {
        int thresholdMinutes = sysConfigService.getInt(KEY_OFFLINE_MINUTES, DEFAULT_OFFLINE_MINUTES);
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(thresholdMinutes);

        // 只在 SQL 侧筛出「启用中、当前标记在线、且最后上报已超过阈值」的站点
        List<Station> stale = stationMapper.selectList(new LambdaQueryWrapper<Station>()
                .eq(Station::getStatus, 1)
                .eq(Station::getOnlineFlag, 1)
                .isNotNull(Station::getLastReportTime)
                .lt(Station::getLastReportTime, deadline));

        int marked = 0;
        for (Station station : stale) {
            try {
                if (markOffline(station, deadline)) {
                    marked++;
                }
            } catch (Exception e) {
                log.error("站点离线标记失败: station={}, err={}", station.getStationCode(), e.getMessage(), e);
            }
        }
        if (marked > 0) {
            log.warn("站点离线检测: {} 个站点超过 {} 分钟未上报，已标记离线", marked, thresholdMinutes);
        }
        return marked;
    }

    /**
     * 条件更新：把在线标记与时间判断一并放进 WHERE。
     * 查询与写入之间可能有新的上报到达并把站点置为在线（采集 Agent 会同时刷新 last_report_time），
     * 若直接按 ID 更新会把这个刚恢复的站点误判为离线，因此必须带上条件由数据库裁决。
     *
     * @return 是否真的标记了离线
     */
    private boolean markOffline(Station station, LocalDateTime deadline) {
        Station update = new Station();
        update.setOnlineFlag(0);
        int rows = stationMapper.update(update, new LambdaQueryWrapper<Station>()
                .eq(Station::getId, station.getId())
                .eq(Station::getOnlineFlag, 1)
                .lt(Station::getLastReportTime, deadline));
        if (rows > 0) {
            log.warn("站点已标记离线: station={}, 最后上报={}",
                    station.getStationCode(), station.getLastReportTime());
            return true;
        }
        return false;
    }
}