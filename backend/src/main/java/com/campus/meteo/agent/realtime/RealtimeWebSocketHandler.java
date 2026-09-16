package com.campus.meteo.agent.realtime;

import com.campus.meteo.influx.ObsData;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时数据 WebSocket 处理器：
 * - 客户端连接后可发送 {"action":"subscribe","stationCode":"C001"} 订阅站点
 * - 采集Agent每处理一条数据即推送 {"type":"latest", ...}
 */
@Slf4j
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    /** 会话 → 已订阅站点编码（"*" 表示全部） */
    private final Map<WebSocketSession, Set<String>> subscriptions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        subscriptions.put(session, ConcurrentHashMap.newKeySet());
        log.info("WebSocket 连接建立: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 解析订阅指令：{"action":"subscribe","stationCode":"C001"}
        try {
            var node = objectMapper.readTree(message.getPayload());
            if ("subscribe".equals(node.path("action").asText())) {
                subscriptions.get(session).add(node.path("stationCode").asText("*"));
            } else if ("unsubscribe".equals(node.path("action").asText())) {
                subscriptions.get(session).remove(node.path("stationCode").asText());
            }
        } catch (Exception e) {
            log.warn("WebSocket 消息解析失败: {}", message.getPayload());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscriptions.remove(session);
        log.info("WebSocket 连接关闭: {}", session.getId());
    }

    /** 采集链路调用：向订阅了该站点的连接推送最新数据 */
    public void push(ObsData obs) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "type", "latest",
                    "stationCode", obs.getStationCode(),
                    "ts", obs.getTs().toString(),
                    "elements", obs.getElements()));
        } catch (Exception e) {
            return;
        }
        subscriptions.forEach((session, stations) -> {
            if (session.isOpen() && (stations.contains("*") || stations.contains(obs.getStationCode()))) {
                try {
                    session.sendMessage(new TextMessage(payload));
                } catch (IOException e) {
                    log.warn("WebSocket 推送失败: sessionId={}, err={}", session.getId(), e.getMessage());
                }
            }
        });
    }
}
