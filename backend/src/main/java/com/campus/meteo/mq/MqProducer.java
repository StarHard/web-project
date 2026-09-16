package com.campus.meteo.mq;

import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Agent 间消息生产者：先落库后发消息，发送失败记录 ERROR 日志（由人工/补偿任务处理）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqProducer {

    private final RabbitTemplate rabbitTemplate;

    /** 发送到指定 routingKey */
    public void send(String routingKey, Object message) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_METEO, routingKey, message);
        } catch (Exception e) {
            log.error("MQ 消息发送失败: routingKey={}, err={}", routingKey, e.getMessage(), e);
            throw new BizException(ErrorCode.MQ_ERROR);
        }
    }
}
