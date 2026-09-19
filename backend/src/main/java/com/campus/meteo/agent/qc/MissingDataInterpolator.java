package com.campus.meteo.agent.qc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.meteo.common.constant.QcFlag;
import com.campus.meteo.entity.Station;
import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsReader;
import com.campus.meteo.influx.ObsWriter;
import com.campus.meteo.mapper.StationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 缺测插补编排（FR-QC-05）：定时扫描各站点近期序列，定位物理缺测并回填带标记的插补值。
 *
 * 判定口径：
 * - 只在「相邻两次观测之间的内部缺口」上插补，且只补**物理缺测**——某槽位若已存在任意质控标记
 *   的数据（含 raw/suspect），说明设备有上报，不属于缺测；被质控拒绝属于数据质量问题，
 *   归人工审核闭环，不能用合成值掩盖
 * - 锚点只取观测值（passed/revised），插补点自身不参与插补，避免长中断被逐段拼接成整段合成数据
 * - 缺口 ≤ maxGapSlots 用线性插值；超过则回退到邻近站点回归；超过 maxNeighborSlots 直接放弃
 *
 * 幂等：插补点写入后即成为「已占用时刻」，下一轮扫描命中占用检查而不再重复补。
 * 插补点不投放 MQ，因此不进入告警链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MissingDataInterpolator {

    /** 多实例防重锁：与 QcAgent 的 msgId 去重同一套 Redis 用法 */
    private static final String LOCK_KEY = "meteo:interpolation:lock";
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final StationMapper stationMapper;
    private final ObsReader obsReader;
    private final ObsWriter obsWriter;
    private final InterpolationService interpolationService;
    private final StringRedisTemplate redisTemplate;

    /** 定时扫描开关（手动触发不受此开关约束，见 interpolateOnce） */
    @Value("${meteo.interpolation.enabled:true}")
    private boolean enabled;
    @Value("${meteo.interpolation.scan-window-hours:3}")
    private int scanWindowHours;
    @Value("${meteo.interpolation.max-gap-slots:2}")
    private int maxGapSlots;
    @Value("${meteo.interpolation.max-neighbor-slots:30}")
    private int maxNeighborSlots;
    @Value("${meteo.interpolation.max-neighbor-distance-km:50}")
    private double maxNeighborDistanceKm;
    @Value("${meteo.interpolation.min-paired-samples:10}")
    private int minPairedSamples;

    /** 定时扫描：用 fixedDelay 而非 cron，避免单次超时的长任务叠加执行 */
    @Scheduled(fixedDelayString = "${meteo.interpolation.scan-delay-ms:600000}", initialDelay = 120_000)
    public void scheduledScan() {
        if (!enabled) {
            return;
        }
        interpolateOnce();
    }

    /**
     * 扫描一轮并回填插补值，定时任务与手动触发共用。
     * 手动触发是运维的显式动作，不受 meteo.interpolation.enabled 约束。
     *
     * @return 本轮统计；未取到锁（其它实例正在跑）时 skippedByLock 为 true
     */
    public InterpolationResult interpolateOnce() {
        if (!tryLock()) {
            log.info("插补任务未取到锁，本轮跳过（可能有其它实例正在执行）");
            return new InterpolationResult(0, 0, 0, true);
        }
        try {
            Instant now = Instant.now();
            Instant from = now.minusSeconds(scanWindowHours * 3600L);
            List<Station> stations = stationMapper.selectList(new LambdaQueryWrapper<Station>()
                    .eq(Station::getStatus, 1)
                    .eq(Station::getOnlineFlag, 1));

            int scanned = 0;
            int written = 0;
            int skipped = 0;
            for (Station station : stations) {
                try {
                    StationResult result = processStation(station, stations, from, now);
                    scanned++;
                    written += result.pointsWritten();
                    skipped += result.gapsSkipped();
                } catch (Exception e) {
                    log.error("站点缺测插补失败: station={}, err={}",
                            station.getStationCode(), e.getMessage(), e);
                }
            }
            if (written > 0 || skipped > 0) {
                log.info("缺测插补完成: 扫描 {} 个站点, 回填 {} 个点, 放弃 {} 个缺口",
                        scanned, written, skipped);
            }
            return new InterpolationResult(scanned, written, skipped, false);
        } finally {
            unlock();
        }
    }

    /** 单站点处理：定位缺口并逐段回填 */
    private StationResult processStation(Station station, List<Station> allStations,
                                         Instant from, Instant now) {
        String code = station.getStationCode();
        List<ObsData> observed = obsReader.queryObservedRange(code, from, now);
        Long interval = interpolationService.inferIntervalSeconds(
                observed.stream().map(ObsData::getTs).toList());
        if (interval == null) {
            log.debug("观测样本不足，跳过插补: station={}, samples={}", code, observed.size());
            return StationResult.EMPTY;
        }

        TreeSet<Instant> occupied = new TreeSet<>(obsReader.queryOccupiedTimestamps(code, from, now));
        long tolerance = Math.max(1, interval / 2);

        int written = 0;
        int skipped = 0;
        for (int i = 0; i < observed.size() - 1; i++) {
            ObsData left = observed.get(i);
            ObsData right = observed.get(i + 1);
            long delta = Duration.between(left.getTs(), right.getTs()).getSeconds();
            if (!interpolationService.isGap(delta, interval)) {
                continue;
            }

            int slots = interpolationService.missingSlots(delta, interval);
            if (slots > maxNeighborSlots) {
                skipped++;
                log.debug("缺口超过上限，放弃: station={}, slots={}", code, slots);
                continue;
            }

            List<Instant> targets = collectTargets(left.getTs(), right.getTs(), slots, interval,
                    occupied, tolerance, now);
            if (targets.isEmpty()) {
                continue;
            }

            if (slots <= maxGapSlots) {
                written += writeLinear(station, left, right, targets);
            } else {
                written += writeByNeighbor(station, allStations, targets, observed,
                        from, now, tolerance);
            }
        }
        return new StationResult(written, skipped);
    }

    /**
     * 计算缺口内需要回填的时刻：按推断间隔等距铺开，落在右锚点之前、当前时刻之前，
     * 且该时刻附近没有任意标记的数据（否则属于设备有上报，不是缺测）。
     */
    private List<Instant> collectTargets(Instant leftTs, Instant rightTs, int slots, long interval,
                                         TreeSet<Instant> occupied, long tolerance, Instant now) {
        List<Instant> targets = new ArrayList<>(slots);
        for (int k = 1; k <= slots; k++) {
            Instant target = leftTs.plusSeconds(k * interval);
            if (!target.isBefore(rightTs) || target.isAfter(now)) {
                continue;
            }
            if (isOccupied(occupied, target, tolerance)) {
                continue;
            }
            targets.add(target);
        }
        return targets;
    }

    /** 目标时刻附近是否已有数据（容差取半个采集间隔，兼容时间戳非整分对齐） */
    private boolean isOccupied(TreeSet<Instant> occupied, Instant target, long toleranceSeconds) {
        Instant floor = occupied.floor(target);
        if (floor != null && Math.abs(Duration.between(floor, target).getSeconds()) <= toleranceSeconds) {
            return true;
        }
        Instant ceiling = occupied.ceiling(target);
        return ceiling != null
                && Math.abs(Duration.between(ceiling, target).getSeconds()) <= toleranceSeconds;
    }

    /** 线性插值回填（风向按圆周插值，降水不参与） */
    private int writeLinear(Station station, ObsData left, ObsData right, List<Instant> targets) {
        int written = 0;
        for (Instant target : targets) {
            Map<String, Double> elements = new LinkedHashMap<>();
            for (String element : InterpolationService.INTERPOLATABLE_ELEMENTS) {
                Double value = interpolationService.interpolateValue(element, left.getTs(),
                        left.getElements().get(element), right.getTs(),
                        right.getElements().get(element), target);
                if (value != null) {
                    elements.put(element, round(value));
                }
            }
            Double direction = interpolationService.interpolateValue(InterpolationService.WIND_DIR,
                    left.getTs(), left.getElements().get(InterpolationService.WIND_DIR),
                    right.getTs(), right.getElements().get(InterpolationService.WIND_DIR), target);
            if (direction != null) {
                elements.put(InterpolationService.WIND_DIR, round(direction));
            }
            if (elements.isEmpty()) {
                continue;
            }
            write(station, target, elements);
            written++;
        }
        return written;
    }

    /**
     * 邻近站点回归回填：取最近站点对应时刻的值，加上两站偏差订正。
     * 风向直接用邻站值不做偏差订正——角度的算术偏差没有物理意义。
     */
    private int writeByNeighbor(Station station, List<Station> allStations, List<Instant> targets,
                                List<ObsData> ownObserved, Instant from, Instant now, long tolerance) {
        Station neighbor = findNearestStation(station, allStations);
        if (neighbor == null) {
            log.debug("无可用邻近站点，放弃回归插补: station={}", station.getStationCode());
            return 0;
        }
        List<ObsData> neighborObserved = obsReader.queryObservedRange(neighbor.getStationCode(), from, now);
        List<InterpolationService.ObsDataView> ownViews = toViews(ownObserved);
        List<InterpolationService.ObsDataView> neighborViews = toViews(neighborObserved);

        InterpolationService.Bias bias = interpolationService.computeBias(ownViews, neighborViews,
                InterpolationService.INTERPOLATABLE_ELEMENTS, tolerance);
        if (bias.pairCount() < minPairedSamples) {
            log.debug("两站配对样本不足，放弃回归插补: station={}, neighbor={}, pairs={}",
                    station.getStationCode(), neighbor.getStationCode(), bias.pairCount());
            return 0;
        }

        int written = 0;
        for (Instant target : targets) {
            InterpolationService.ObsDataView theirs =
                    interpolationService.nearest(neighborViews, target, tolerance);
            if (theirs == null) {
                continue;
            }
            Map<String, Double> elements = new LinkedHashMap<>();
            for (String element : InterpolationService.INTERPOLATABLE_ELEMENTS) {
                Double base = theirs.elements().get(element);
                Double offset = bias.values().get(element);
                if (base != null && offset != null) {
                    elements.put(element, round(base + offset));
                }
            }
            Double direction = theirs.elements().get(InterpolationService.WIND_DIR);
            if (direction != null) {
                elements.put(InterpolationService.WIND_DIR, round(direction));
            }
            if (elements.isEmpty()) {
                continue;
            }
            write(station, target, elements);
            written++;
        }
        log.info("邻近站点回归回填: station={}, neighbor={}, pairs={}, points={}",
                station.getStationCode(), neighbor.getStationCode(), bias.pairCount(), written);
        return written;
    }

    /** 按 Haversine 距离取最近站点，超出距离上限视为不可用 */
    private Station findNearestStation(Station self, List<Station> candidates) {
        if (self.getLongitude() == null || self.getLatitude() == null) {
            return null;
        }
        double selfLon = self.getLongitude().doubleValue();
        double selfLat = self.getLatitude().doubleValue();
        Station nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Station candidate : candidates) {
            if (candidate.getId() == null || candidate.getId().equals(self.getId())
                    || candidate.getLongitude() == null || candidate.getLatitude() == null) {
                continue;
            }
            double distance = interpolationService.distanceKm(selfLon, selfLat,
                    candidate.getLongitude().doubleValue(), candidate.getLatitude().doubleValue());
            if (distance <= maxNeighborDistanceKm && distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    /** 写入插补点：统一走 ObsWriter，标记 qc_flag=interpolated（FR-QC-05 的「须带标记」） */
    private void write(Station station, Instant ts, Map<String, Double> elements) {
        ObsData obs = ObsData.builder()
                .stationCode(station.getStationCode())
                .ts(ts)
                .elements(elements)
                .qcFlag(QcFlag.INTERPOLATED.getValue())
                .msgId("interp-" + station.getStationCode() + "-" + ts.getEpochSecond())
                .build();
        obsWriter.writeObs(obs);
    }

    private List<InterpolationService.ObsDataView> toViews(List<ObsData> list) {
        List<InterpolationService.ObsDataView> views = new ArrayList<>(list.size());
        for (ObsData obs : list) {
            views.add(new InterpolationService.ObsDataView(obs.getTs(), obs.getElements()));
        }
        return views;
    }

    private double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private boolean tryLock() {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL));
        } catch (Exception e) {
            log.warn("插补任务取锁异常，本轮跳过: {}", e.getMessage());
            return false;
        }
    }

    private void unlock() {
        try {
            redisTemplate.delete(LOCK_KEY);
        } catch (Exception e) {
            log.warn("插补任务释放锁异常: {}", e.getMessage());
        }
    }

    /** 单站点统计 */
    private record StationResult(int pointsWritten, int gapsSkipped) {
        private static final StationResult EMPTY = new StationResult(0, 0);
    }

    /** 本轮统计：skippedByLock 表示因其它实例持锁而未执行 */
    public record InterpolationResult(int stationsScanned, int pointsWritten,
                                      int gapsSkipped, boolean skippedByLock) {
    }
}