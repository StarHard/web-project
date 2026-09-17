package com.campus.meteo.agent.alert;

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
 * 告警 WebSocket 处理器：告警事件全量广播（预警信号属公共服务信息）
 */
@Slf4j
@Component
public class AlertWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("告警WebSocket连接建立: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("告警WebSocket连接关闭: {}", session.getId());
    }

    /** 广播告警事件：{"type":"alert","stationCode":...,"level":...,"content":...,"alertTime":...} */
    public void broadcast(String stationCode, int level, String content, String alertTime) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "type", "alert",
                    "stationCode", stationCode,
                    "level", level,
                    "content", content,
                    "alertTime", alertTime));
        } catch (Exception e) {
            return;
        }
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(payload));
                } catch (IOException e) {
                    log.warn("告警推送失败: sessionId={}, err={}", session.getId(), e.getMessage());
                }
            }
        }
    }
}
