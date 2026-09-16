package com.campus.meteo.common.constant;

/**
 * RabbitMQ Topic 常量，Agent 间通信契约（见《系统架构设计》2.3.2 节）
 */
public final class MqTopics {

    private MqTopics() {
    }

    /** 采集Agent → 质控Agent：原始标准化数据 */
    public static final String METEO_RAW = "topic.meteo.raw";

    /** 质控Agent → 告警Agent/报表Agent：质控后数据 */
    public static final String METEO_QC = "topic.meteo.qc";

    /** 告警Agent → 通知服务：告警事件 */
    public static final String ALERT_TRIGGER = "topic.alert.trigger";

    /** 预报Agent → Web应用层：预报产品就绪 */
    public static final String FORECAST_READY = "topic.forecast.ready";
}
