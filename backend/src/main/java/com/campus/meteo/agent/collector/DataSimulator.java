package com.campus.meteo.agent.collector;

import com.campus.meteo.common.constant.QcFlag;
import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsReader;
import com.campus.meteo.influx.ObsWriter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据模拟器：开发/测试环境模拟多站点传感器上报（直接走采集 Agent 处理逻辑，等价于收到 MQTT 消息）
 *
 * <p><b>物理建模口径（这一版的核心改动）</b>：所有要素都是「时刻的连续函数」，
 * 而不是「每次调用随机推进一步」。原实现把步长绑在调用次数上——
 * 采样间隔从默认 60 秒改成开发环境的 15 秒，物理变化速率就跟着快 4 倍，
 * 同一份参数在两种间隔下不可能都真实（实测出现气温 14℃/小时、气压 11.9 hPa/小时 的跳变）。
 * 改成时刻的函数之后，采样间隔只影响抽样密度，不影响物理速率。
 *
 * <p>具体耦合关系：
 * <ul>
 *   <li><b>日变化</b>：气温按正弦（15 时最高、日出前最低）；辐射按太阳高度角的钟形曲线，
 *       夜间严格为 0；日长与辐射峰值随季节变化。</li>
 *   <li><b>气候标定</b>：年均气温与年振幅按站点所在地闽侯（福州）口径取值
 *       （基线 21.3℃、年振幅 9.0℃、日较差约 8.4℃），不是通用温带口径；
 *       云量对气温的削减只在白天生效。</li>
 *   <li><b>温湿反相</b>：湿度与气温反相（越热越干），这是真实的日变化特征。</li>
 *   <li><b>天气过程</b>：一个缓慢的「湿度/扰动」通道同时驱动云量、气压、风速——
 *       云量高则辐射被削减、气压降低、风速抬升、能见度下降，符合降水天气的物理图景。</li>
 *   <li><b>降水为间歇事件</b>：由云量超过阈值触发，自然形成「下几小时、停一段」的形态。
 *       原实现逐条独立掷骰子（12%/条），在小时尺度上退化成「每小时都有雨」。</li>
 *   <li><b>局地偏差有界</b>：相距十几公里的站点实况应高度相关，偏差幅度控制在物理合理范围内。</li>
 *   <li><b>历史回填</b>：启动时若该站点历史观测为空，就用同一套模型补齐 N 天逐小时观测。
 *       历史与实时出自同一份公式，正是本类承担回填的全部理由——旧做法由独立的 PowerShell
 *       脚本灌历史，两套模型互不相干，边界处会出现「历史说湿度 76%、实时说 94%」这类跳变，
 *       质控的时间一致性检验会如实把实时数据判为可疑，结果质控通过的数据长时间冻结、
 *       审核队列被垃圾任务刷满。</li>
 *   <li><b>重启接续</b>：要素是时刻的函数，重启后天然连续，无需再从库里读最近观测续跑。</li>
 * </ul>
 *
 * <p><b>尖峰注入</b>：仍保留（用于演示质控 Agent 的极值拦截），但限流为每站每小时最多一次。
 * 原来 5%/条 的频率在 15 秒节奏下等于每天数百条审核任务，会把质控审核队列刷爆。
 *
 * 开启方式：meteo.simulator.enabled=true；间隔 meteo.simulator.interval-ms（默认60秒）
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "meteo.simulator.enabled", havingValue = "true")
public class DataSimulator {

    private static final String[] STATIONS = {"CAMPUS01", "FARM02"};

    /**
     * 多尺度波动周期（小时）与权重。叠加后得到平滑、不重复、且**频率与调用次数无关**的天气过程：
     * 最短 7.3 小时保证「几小时内缓慢演变」，最长 83.3 小时给出「几天一轮的天气过程」。
     */
    private static final double[] WAVE_PERIOD_HOURS = {7.3, 19.1, 41.7, 83.3};
    private static final double[] WAVE_WEIGHTS = {1.0, 0.72, 0.45, 0.30};

    /** 气温年变化：按站点所在地闽侯（福州）口径标定。
     *  福州年均气温 20.4℃、年振幅 9.0℃，但云量削减只作用于白天、平均把气温拉低约 0.45℃，
     *  故基线取 21.3℃ 才让实现值回到实况——1 月约 12.0℃、7 月约 29.7℃、9 月约 26.5℃
     *  （福州实况 11.5 / 29.5 / 26.5）。
     *  原值 18.5 / 11.5 是温带内陆口径，站点迁到闽侯后夜间最低温比实况低 5~6℃。 */
    private static final double TEMP_ANNUAL_MEAN = 21.3;
    private static final double TEMP_ANNUAL_AMPLITUDE = 9.0;
    /** 气温日变化振幅 ±4.2℃。福州 9 月下旬实况日较差约 7.3℃，
     *  取 8.4℃ 再扣掉白天云量削减后的周均值才落在这个量级（±3.8 会偏平）。 */
    private static final double TEMP_DIURNAL_AMPLITUDE = 4.2;
    /** 天气过程带来的气温起伏 ±2.5℃；云量对气温的削减（仅白天生效，见 computeElements） */
    private static final double TEMP_SYNOPTIC_AMPLITUDE = 2.5;
    private static final double TEMP_CLOUD_COOLING = 2.6;

    private static final double HUMI_BASE = 72.0;
    private static final double HUMI_DIURNAL_AMPLITUDE = 20.0;
    private static final double HUMI_SYNOPTIC_AMPLITUDE = 4.0;
    private static final double HUMI_CLOUD_BOOST = 12.0;
    /** 起雨之后的额外湿度抬升：真实降雨时相对湿度通常在 85% 以上 */
    private static final double HUMI_RAIN_BOOST = 10.0;

    private static final double PRES_BASE = 1013.0;
    private static final double PRES_SYNOPTIC_AMPLITUDE = 7.0;

    private static final double WIND_BASE = 2.6;
    private static final double WIND_SYNOPTIC_AMPLITUDE = 2.0;
    private static final double WIND_GUST_AMPLITUDE = 1.6;
    /** 扰动通道为正时抬升风速（锋面/风暴），使 >10 m/s 偶发而非长期持续 */
    private static final double WIND_STORM_BOOST = 6.5;
    private static final double WIND_MAX = 20.0;

    /** 辐射晴天峰值：夏至约 830、冬至约 570 W/m²；云量对辐射的削减（满云保留 20%） */
    private static final double RAD_PEAK_MEAN = 700.0;
    private static final double RAD_PEAK_ANNUAL_AMPLITUDE = 130.0;
    private static final double RAD_CLOUD_ATTENUATION = 0.80;

    /** 日长：夏至约 13.6 小时、冬至约 10.4 小时 */
    private static final double DAY_LENGTH_MEAN = 12.0;
    private static final double DAY_LENGTH_AMPLITUDE = 1.6;

    /** 云量超过该阈值才开始降水（区间上限对应约 5.5 mm/h 的最大雨强） */
    private static final double CLOUD_RAIN_THRESHOLD = 0.72;
    private static final double RAIN_MAX_INTENSITY = 5.5;

    private static final double WIND_DIR_BASE = 200.0;
    private static final double WIND_DIR_SYNOPTIC_AMPLITUDE = 28.0;

    private static final double VIS_BASE = 30.0;
    private static final double VIS_CLOUD_ATTENUATION = 24.0;

    /** 圆周量：风向不做差值类判定，也不参与线性统计 */
    private static final Set<String> CIRCULAR_ELEMENTS = Set.of("wind_dir");

    /** 每站每小时最多注入一次异常尖峰 */
    private static final double SPIKE_PROBABILITY_PER_TICK = 0.005;

    /**
     * 站点局地偏差（相对大尺度天气背景）。幅度有界且恒定：
     * 农业站略偏暖、湿度略低，与历史演示数据的口径保持一致。
     */
    private static final Map<String, Map<String, Double>> STATION_OFFSETS = Map.of(
            "CAMPUS01", Map.of("temp", 0.0, "humi", 0.0, "pres", 0.0, "wind_speed", -0.3, "wind_dir", -8.0),
            "FARM02", Map.of("temp", 1.2, "humi", -2.0, "pres", -0.4, "wind_speed", 0.4, "wind_dir", 10.0));

    private final CollectorAgent collectorAgent;
    private final ObsWriter obsWriter;
    private final ObsReader obsReader;
    private final Random random = new Random();

    /** 启动时回填的历史天数；0 表示不回填 */
    @Value("${meteo.simulator.backfill-days:7}")
    private int backfillDays;

    /** 通道 → 各周期的相位（由固定种子生成，保证同一通道的波形可复现） */
    private final Map<String, double[]> channelPhases = new ConcurrentHashMap<>();
    /** 站点 → 最近注入尖峰的小时序号 */
    private final Map<String, Long> lastSpikeHour = new ConcurrentHashMap<>();

    public DataSimulator(CollectorAgent collectorAgent, ObsWriter obsWriter, ObsReader obsReader) {
        this.collectorAgent = collectorAgent;
        this.obsWriter = obsWriter;
        this.obsReader = obsReader;
    }

    @Scheduled(fixedDelayString = "${meteo.simulator.interval-ms:60000}", initialDelay = 5_000)
    public void publish() {
        long now = System.currentTimeMillis();
        for (String stationCode : STATIONS) {
            Map<String, Double> elements = computeElements(stationCode, now);
            // 尖峰只注入实时链路：回填历史必须是干净数据，否则会把 -50℃ 这种不可能的值
            // 以 qc_flag=passed 直接写进时序库——它不经过质控，也就没有人工审核兜底
            maybeInjectSpike(stationCode, elements, now);
            // 风向按 1 位小数上报，与 computeElements 的 round1 口径一致。
            // 原模板写 %.0f，导致实时链路存整数、回填存 1 位小数，同一条曲线里混着两种精度
            String payload = """
                    {"stationCode":"%s","ts":%d,"elements":{
                    "temp":%.1f,"humi":%.1f,"pres":%.1f,"wind_speed":%.1f,"wind_dir":%.1f,
                    "rain":%.1f,"rad":%.1f,"vis":%.1f,"evap":%.2f}}
                    """.formatted(stationCode, now,
                    elements.get("temp"), elements.get("humi"), elements.get("pres"),
                    elements.get("wind_speed"), elements.get("wind_dir"), elements.get("rain"),
                    elements.get("rad"), elements.get("vis"), elements.get("evap"));
            try {
                MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                message.setQos(1);
                collectorAgent.processMessage("meteo/" + stationCode + "/up", message);
            } catch (Exception e) {
                log.error("模拟器上报失败: station={}, err={}", stationCode, e.getMessage());
            }
        }
    }

    /**
     * 纯函数：给定站点与时刻，算出该时刻的观测要素。
     *
     * <p>抽成无副作用的方法是为了可单测——物理合理性断言不需要起 Spring、不需要 MQTT、
     * 也不需要真等上一整天，直接用不同时刻调用即可。
     */
    Map<String, Double> computeElements(String stationCode, long epochMillis) {
        LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
        double hourOfDay = local.getHour() + local.getMinute() / 60.0;
        double monthIndex = local.getMonthValue() - 1;

        // 季节相位：夏至（6 月下旬）取峰值
        double seasonal = Math.sin(2 * Math.PI * (monthIndex - 3.3) / 12);
        // 日变化相位：15 时取峰值
        double diurnal = 2 * Math.PI * (hourOfDay - 9) / 24;

        // 日长与日出日落随季节变化；同时给出白昼权重 daylight（夜间 0、正午附近 1）。
        // 辐射与「云量削减气温」都依赖它——真实大气里云在夜间是保温的
        // （抑制长波辐射降温），若全天扣温会把夜间最低温再压低一档。
        double dayLength = DAY_LENGTH_MEAN + DAY_LENGTH_AMPLITUDE * seasonal;
        double sunrise = 12.0 - dayLength / 2.0;
        double sunset = 12.0 + dayLength / 2.0;
        double daylight = hourOfDay >= sunrise && hourOfDay <= sunset
                ? Math.sin(Math.PI * (hourOfDay - sunrise) / dayLength)
                : 0.0;

        // 天气过程通道：同一个「扰动强度」驱动云量、气压与风速，保证三者物理上同源
        double wet = slowWave("wet", epochMillis);
        double cloud = clamp(0.5 + 0.62 * wet, 0.0, 1.0);
        // 降水强度因子（0~1）：云量超过阈值后才起雨。同时用于湿度的额外抬升，
        // 保证「有雨时湿度明显更高」这条物理关系成立，而不是只与云量弱相关
        double rainFactor = Math.max(0.0, cloud - CLOUD_RAIN_THRESHOLD) / (1.0 - CLOUD_RAIN_THRESHOLD);
        double rain = rainFactor > 0.0 ? round1(0.3 + rainFactor * RAIN_MAX_INTENSITY) : 0.0;

        double tempOffset = stationOffset(stationCode, "temp");
        double temp = TEMP_ANNUAL_MEAN + TEMP_ANNUAL_AMPLITUDE * seasonal
                + TEMP_DIURNAL_AMPLITUDE * Math.sin(diurnal)
                + TEMP_SYNOPTIC_AMPLITUDE * slowWave("temp", epochMillis)
                - TEMP_CLOUD_COOLING * cloud * daylight
                + tempOffset + noise(0.25);
        temp = clamp(temp, -40.0, 50.0);

        double humi = HUMI_BASE - HUMI_DIURNAL_AMPLITUDE * Math.sin(diurnal)
                + HUMI_SYNOPTIC_AMPLITUDE * slowWave("humi", epochMillis)
                + HUMI_CLOUD_BOOST * cloud
                + HUMI_RAIN_BOOST * rainFactor
                + stationOffset(stationCode, "humi") + noise(1.5);
        humi = clamp(humi, 15.0, 100.0);

        // 气压与云量同源反相：云量高（降水天气）对应低压。
        // 只取长周期分量：气压在数小时内应缓慢变化（真实约 1~3 hPa/小时），不能被小时级波动带快
        double pres = PRES_BASE - PRES_SYNOPTIC_AMPLITUDE * slowWaveSlow("pres", epochMillis)
                + stationOffset(stationCode, "pres") + noise(0.3);

        double windSpeed = WIND_BASE
                + WIND_SYNOPTIC_AMPLITUDE * slowWave("wind", epochMillis)
                + WIND_GUST_AMPLITUDE * slowWave("gust", epochMillis)
                + WIND_STORM_BOOST * Math.max(0.0, wet)
                + stationOffset(stationCode, "wind_speed") + noise(0.4);
        windSpeed = clamp(windSpeed, 0.0, WIND_MAX);

        double windDir = normalizeDegrees(WIND_DIR_BASE
                + WIND_DIR_SYNOPTIC_AMPLITUDE * wet
                + 14.0 * slowWave("dir", epochMillis)
                + stationOffset(stationCode, "wind_dir"));

        // 辐射：夜间严格为 0（daylight 为 0 时不叠噪声，否则会凭空出现非零辐射）
        double radPeak = RAD_PEAK_MEAN + RAD_PEAK_ANNUAL_AMPLITUDE * seasonal;
        double radClear = radPeak * daylight;
        double rad = radClear <= 0.0
                ? 0.0
                : Math.max(0.0, radClear * (1.0 - RAD_CLOUD_ATTENUATION * cloud) * (1.0 + noise(0.03)));

        double vis = clamp(VIS_BASE - VIS_CLOUD_ATTENUATION * cloud + noise(1.0), 1.0, 40.0);

        // 蒸发与辐射同向：夜间无辐射则蒸发接近 0
        double evap = clamp(0.10 + 0.55 * (radClear / RAD_PEAK_MEAN) - 0.25 * cloud + noise(0.03), 0.0, 2.0);

        Map<String, Double> elements = new LinkedHashMap<>();
        elements.put("temp", round1(temp));
        elements.put("humi", round1(humi));
        elements.put("pres", round1(pres));
        elements.put("wind_speed", round1(windSpeed));
        elements.put("wind_dir", normalizeDegrees(round1(windDir)));
        elements.put("rain", rain);
        elements.put("rad", round1(rad));
        elements.put("vis", round1(vis));
        elements.put("evap", round2(evap));

        return elements;
    }

    /**
     * 多尺度正弦叠加的缓慢波动，取值约落在 [-1, 1]（实际多在 ±0.7）。
     *
     * <p>相位由「通道名 + 固定种子」决定：同一通道每次调用得到相同波形，
     * 不同通道互不相关，因此各要素既平滑又不互相绑死。
     */
    private double slowWave(String channel, long epochMillis) {
        return slowWave(channel, epochMillis, 0);
    }

    /**
     * 只取长周期分量（41.7h / 83.3h）。
     *
     * <p>气压这类要素必须用它：真实气压在数小时内只变化 1~3 hPa，
     * 若混入 7.3 小时周期分量，小时变化率会被放大到不真实、并撞上质控阈值。
     */
    private double slowWaveSlow(String channel, long epochMillis) {
        return slowWave(channel, epochMillis, 2);
    }

    private double slowWave(String channel, long epochMillis, int fromIndex) {
        double[] phases = channelPhases.computeIfAbsent(channel, key -> {
            Random seeded = new Random(1_000L + key.hashCode());
            double[] result = new double[WAVE_PERIOD_HOURS.length];
            for (int i = 0; i < result.length; i++) {
                result[i] = seeded.nextDouble() * 2 * Math.PI;
            }
            return result;
        });
        double hours = epochMillis / 3_600_000.0;
        double sum = 0.0;
        double weightSum = 0.0;
        for (int i = fromIndex; i < WAVE_PERIOD_HOURS.length; i++) {
            sum += WAVE_WEIGHTS[i] * Math.sin(2 * Math.PI * hours / WAVE_PERIOD_HOURS[i] + phases[i]);
            weightSum += WAVE_WEIGHTS[i];
        }
        return weightSum == 0 ? 0.0 : sum / weightSum;
    }

    private double stationOffset(String stationCode, String element) {
        Map<String, Double> offsets = STATION_OFFSETS.get(stationCode);
        return offsets == null ? 0.0 : offsets.getOrDefault(element, 0.0);
    }

    private double noise(double amplitude) {
        return (random.nextDouble() * 2 - 1) * amplitude;
    }

    /**
     * 启动时用同一套物理模型回填历史观测（逐小时）。
     *
     * <p>只在该站点的历史窗口内没有任何观测时才回填，因此重启不会重复写入。
     * 区间末端留出 1 小时给实时链路，避免与刚写入的实时数据重叠。
     * 回填以 qc_flag=passed 直接落库：这些值本身就出自与实时相同的公式，
     * 再走一遍质控只会让启动变慢，且质控的 1 小时基准窗口也无从比对。
     *
     * <p>回填失败不阻断启动——实时链路不依赖历史数据。
     */
    @PostConstruct
    void backfillHistoryIfEmpty() {
        if (backfillDays <= 0) {
            return;
        }
        Instant to = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        Instant from = to.minus(backfillDays, ChronoUnit.DAYS);
        for (String stationCode : STATIONS) {
            try {
                if (!obsReader.queryObservedRange(stationCode, from, to).isEmpty()) {
                    log.info("历史观测已存在，跳过回填: station={}", stationCode);
                    continue;
                }
                int written = 0;
                for (Instant ts = from; !ts.isAfter(to); ts = ts.plus(1, ChronoUnit.HOURS)) {
                    obsWriter.writeObs(ObsData.builder()
                            .stationCode(stationCode)
                            .ts(ts)
                            .qcFlag(QcFlag.PASSED.getValue())
                            .elements(computeElements(stationCode, ts.toEpochMilli()))
                            .build());
                    written++;
                }
                log.info("历史回填完成: station={}, 条数={}, 区间={} ~ {}", stationCode, written, from, to);
            } catch (Exception e) {
                log.warn("历史回填失败，跳过该站点: station={}, err={}", stationCode, e.getMessage());
            }
        }
    }

    /**
     * 注入异常气温尖峰，用于演示质控的极值拦截与人工审核任务。
     * 限流为每站每小时最多一次：原来的 5%/条 在 15 秒节奏下等于每天数百条审核任务。
     */
    private void maybeInjectSpike(String stationCode, Map<String, Double> elements, long epochMillis) {
        long currentHour = epochMillis / 3_600_000L;
        Long lastHour = lastSpikeHour.get(stationCode);
        if (lastHour != null && lastHour == currentHour) {
            return;
        }
        if (random.nextDouble() >= SPIKE_PROBABILITY_PER_TICK) {
            return;
        }
        lastSpikeHour.put(stationCode, currentHour);
        double spike = -50 - random.nextDouble() * 10;
        elements.put("temp", round1(spike));
        log.info("模拟器注入异常气温尖峰: station={}, value={}", stationCode, spike);
    }

    /** 风向归一化到 [0, 360)：圆周量不能只做上下界截断，否则会在 0/360 处堆积 */
    private double normalizeDegrees(double degrees) {
        double result = degrees % 360.0;
        return result < 0 ? result + 360.0 : result;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }
}