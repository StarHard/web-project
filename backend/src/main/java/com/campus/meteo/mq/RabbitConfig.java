package com.campus.meteo.mq;

import com.campus.meteo.common.constant.MqTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
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

    /**
     * JSON 消息转换器：Agent 间传输 ObsData 等 POJO。
     * 默认 SimpleMessageConverter 只支持 String/byte[]/Serializable，POJO 会发送失败。
     * 生产端与消费端共用（Spring Boot 自动装配到 RabbitTemplate 与监听容器工厂）。
     */
    @Bean
    public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /** 监听容器定制：应用手动 ack 配置（application.yml 中已声明，此处兜底声明监听器类型） */
    @Bean
    public ContainerCustomizer<SimpleMessageListenerContainer> containerCustomizer() {
        return container -> container.setDefaultRequeueRejected(false);
    }
}
