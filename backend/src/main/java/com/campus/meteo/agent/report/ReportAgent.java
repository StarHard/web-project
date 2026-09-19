package com.campus.meteo.agent.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.meteo.dto.ReportGenerateReq;
import com.campus.meteo.entity.Station;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 报表Agent：按日/月/年定时聚合历史数据，为每个正常站点生成统计报表并登记 report_file。
 *
 * 触发方式与《系统架构设计》2.3.1 一致——定时（日/月/年），输入 InfluxDB 历史数据，
 * 因此不订阅 topic.meteo.qc 实时流（原设计中绑定给报表 Agent 的队列长期无消费者，
 * 只会单向堆积，已从 RabbitConfig 移除）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportAgent {

    /** 报表类型：1日报 2月报 3年报 */
    private static final int TYPE_DAILY = 1;
    private static final int TYPE_MONTHLY = 2;
    private static final int TYPE_YEARLY = 3;

    private final StationMapper stationMapper;
    private final ReportService reportService;

    /** 日报：每日 02:00 生成前一日 */
    @Scheduled(cron = "0 0 2 * * *")
    public void generateDailyReports() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        generateForAllStations(TYPE_DAILY, yesterday, yesterday, "日报");
    }

    /** 月报：每月 1 日 02:30 生成上一自然月 */
    @Scheduled(cron = "0 30 2 1 * *")
    public void generateMonthlyReports() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        generateForAllStations(TYPE_MONTHLY, lastMonth.atDay(1), lastMonth.atEndOfMonth(), "月报");
    }

    /** 年报：每年 1 月 1 日 03:00 生成上一自然年 */
    @Scheduled(cron = "0 0 3 1 1 *")
    public void generateYearlyReports() {
        int lastYear = LocalDate.now().getYear() - 1;
        generateForAllStations(TYPE_YEARLY,
                LocalDate.of(lastYear, 1, 1), LocalDate.of(lastYear, 12, 31), "年报");
    }

    /**
     * 遍历正常站点逐个生成，单站失败只记录不中断，避免一个站点的数据问题拖垮整批。
     * 定时任务无登录上下文，createBy 传 null（见 report_file.create_by 注释）。
     */
    private void generateForAllStations(int reportType, LocalDate start, LocalDate end, String label) {
        List<Station> stations = stationMapper.selectList(new LambdaQueryWrapper<Station>()
                .eq(Station::getStatus, 1));
        int success = 0;
        for (Station station : stations) {
            try {
                ReportGenerateReq req = new ReportGenerateReq();
                req.setStationId(station.getId());
                req.setReportType(reportType);
                req.setPeriodStart(start);
                req.setPeriodEnd(end);
                reportService.generate(req, null);
                success++;
            } catch (Exception e) {
                log.error("{}生成失败: station={}, period={} ~ {}, err={}",
                        label, station.getStationCode(), start, end, e.getMessage(), e);
            }
        }
        log.info("{}生成完成: 成功 {}/{} 个站点, 周期 {} ~ {}", label, success, stations.size(), start, end);
    }
}