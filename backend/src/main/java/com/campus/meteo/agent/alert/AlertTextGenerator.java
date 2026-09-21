package com.campus.meteo.agent.alert;

import com.campus.meteo.common.constant.MeteoElement;
import com.campus.meteo.entity.AlertRule;
import com.campus.meteo.influx.ObsData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 预警文案生成器：把规则判定结果扩写成面向人的处置建议
 *
 * 与模板文案的分工：模板只说得清「超了多少」，说不清「该怎么办」——而后者恰恰决定处置效率。
 * 本类负责补上影响对象与可执行动作，且只依据传入的告警事实与观测快照写作，不引入模型的外部常识。
 *
 * 定位：不是独立 Agent（不占用 MQ 通道、不参与 Agent 间通信），只是告警链路内的一个内容生成组件，
 * 因此由 AlertAgent 直接调用。
 *
 * 失败语义：大模型是外部依赖，生成失败一律返回 null 由调用方回退模板文案——
 * 预警记录绝不因大模型不可用而缺失或延迟落库。
 */
@Slf4j
@Component
public class AlertTextGenerator {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 告警类型 / 等级名称，与 AlertAgent 的判定口径保持一致 */
    private static final String[] TYPE_NAMES = {"", "暴雨", "大风", "高温", "寒潮", "冰雹"};
    private static final String[] LEVEL_NAMES = {"", "蓝色", "黄色", "橙色", "红色"};

    /** 文案长度上限：与 alert_record.ai_content 列宽（VARCHAR(500)）留出余量 */
    private static final int MAX_LENGTH = 200;

    private static final String SYSTEM_PROMPT = """
            你是校园气象预警文案撰写员，读者是高校后勤、宿管与农业试验站的值班人员。
            写作要求：
            1. 只依据给出的告警事实与观测数据写作，严禁补充未提供的数值、趋势或预测；
            2. 先一句话点明受影响的对象（户外人员、高空作业、临时搭建物、试验棚架、排水等），
               再给 2-3 条可立即执行的处置动作；
            3. 用纯文本，不要 Markdown 标记、不要标题、不要重复罗列已给出的数值；
            4. 全文不超过 120 字。
            """;

    private final ChatClient chatClient;

    /**
     * 注入的是 prototype 作用域的 Builder，这里构建的是只服务于预警文案的独立客户端。
     * 不复用决策助手的客户端：两者的系统提示词完全不同，共用会让「助手式作答」污染预警文案。
     */
    public AlertTextGenerator(ChatClient.Builder builder) {
        this.chatClient = builder.defaultSystem(SYSTEM_PROMPT).build();
    }

    /**
     * 生成预警处置建议
     *
     * @param fromLevel 升级前的等级；非升级场景传 null
     * @return 生成失败（未配置密钥、网络不通、返回为空）时返回 null
     */
    public String generate(String stationName, AlertRule rule, ObsData obs, int level, double value,
                           Integer fromLevel) {
        try {
            String text = chatClient.prompt()
                    .user(buildPrompt(stationName, rule, obs, level, value, fromLevel))
                    .call()
                    .content();
            if (!StringUtils.hasText(text)) {
                log.warn("预警文案返回为空，回退模板文案: station={}, rule={}",
                        obs.getStationCode(), rule.getId());
                return null;
            }
            return truncate(text.trim());
        } catch (Exception e) {
            log.warn("预警文案生成失败，回退模板文案: station={}, rule={}, err={}",
                    obs.getStationCode(), rule.getId(), e.getMessage());
            return null;
        }
    }

    /** 提示词构建（包级可见，便于单测直接断言提示词把告警事实交代清楚） */
    String buildPrompt(String stationName, AlertRule rule, ObsData obs, int level, double value,
                       Integer fromLevel) {
        StringBuilder prompt = new StringBuilder("告警事实：\n");
        prompt.append("- 站点：").append(stationName)
                .append("（编码 ").append(obs.getStationCode()).append("）\n");
        prompt.append("- 告警类型：").append(typeName(rule.getAlertType())).append("\n");
        prompt.append("- 告警等级：").append(levelName(level)).append("\n");
        prompt.append("- 触发要素：").append(MeteoElement.label(rule.getElement()))
                .append(" = ").append(round(value))
                .append("，阈值 ").append(plain(rule.getThreshold().doubleValue()))
                .append("（判定条件：").append(conditionName(rule)).append("）\n");
        prompt.append("- 触发时刻：").append(TIME_FMT.format(LocalDateTime.ofInstant(obs.getTs(), ZONE))).append("\n");
        if (fromLevel != null) {
            prompt.append("- 本次为等级升级：由").append(levelName(fromLevel))
                    .append("升级为").append(levelName(level)).append("\n");
        }
        prompt.append("- 该站同期观测：").append(formatElements(obs.getElements())).append("\n");
        prompt.append("\n请据此写一段不超过 120 字的预警处置建议。");
        return prompt.toString();
    }

    private String conditionName(AlertRule rule) {
        return switch (rule.getCondition()) {
            case 1 -> "大于阈值";
            case 2 -> "小于阈值";
            case 3 -> "持续 %d 分钟超限".formatted(rule.getDurationMin() == null ? 60 : rule.getDurationMin());
            default -> "未知条件";
        };
    }

    private String formatElements(Map<String, Double> elements) {
        if (elements == null || elements.isEmpty()) {
            return "无";
        }
        return elements.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> "%s %s".formatted(MeteoElement.label(entry.getKey()), round(entry.getValue())))
                .collect(Collectors.joining("、"));
    }

    /** 超长时截断并加省略号，避免超出列宽被数据库静默截断 */
    private String truncate(String text) {
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH - 1) + "…";
    }

    private String typeName(int alertType) {
        return alertType > 0 && alertType < TYPE_NAMES.length ? TYPE_NAMES[alertType] : "未知";
    }

    private String levelName(int level) {
        return level > 0 && level < LEVEL_NAMES.length ? LEVEL_NAMES[level] : "未知";
    }

    private String round(double value) {
        return plain(Math.round(value * 10) / 10.0);
    }

    /** 去掉浮点尾差与多余的 .0，提示词里出现 "10.0" 容易被模型原样抄进文案 */
    private String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
