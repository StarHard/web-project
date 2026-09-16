package com.campus.meteo.mq;

import com.campus.meteo.common.constant.MqTopics;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明：Topic 交换机 + 各 Agent 消费队列
 */
@Configuration
public class RabbitConfig {

    /** 采集链路统一交换机 */
    public static final String EXCHANGE_METEO = "exchange.meteo";

    public static final String QUEUE_METEO_RAW = "queue.meteo.raw";      // 采集 → 质控
    public static final String QUEUE_METEO_QC_ALERT = "queue.meteo.qc.alert";  // 质控 → 告警
    public static final String QUEUE_METEO_QC_REPORT = "queue.meteo.qc.report"; // 质控 → 报表

    @Bean
    public TopicExchange meteoExchange() {
        return new TopicExchange(EXCHANGE_METEO, true, false);
    }

    @Bean
    public Queue meteoRawQueue() {
        return new Queue(QUEUE_METEO_RAW, true);
    }

    @Bean
    public Queue meteoQcAlertQueue() {
        return new Queue(QUEUE_METEO_QC_ALERT, true);
    }

    @Bean
    public Queue meteoQcReportQueue() {
        return new Queue(QUEUE_METEO_QC_REPORT, true);
    }

    @Bean
    public Binding bindingRaw(Queue meteoRawQueue, TopicExchange meteoExchange) {
        return BindingBuilder.bind(meteoRawQueue).to(meteoExchange).with(MqTopics.METEO_RAW);
    }

    @Bean
    public Binding bindingQcAlert(Queue meteoQcAlertQueue, TopicExchange meteoExchange) {
        return BindingBuilder.bind(meteoQcAlertQueue).to(meteoExchange).with(MqTopics.METEO_QC);
    }

    @Bean
    public Binding bindingQcReport(Queue meteoQcReportQueue, TopicExchange meteoExchange) {
        return BindingBuilder.bind(meteoQcReportQueue).to(meteoExchange).with(MqTopics.METEO_QC);
    }
}
