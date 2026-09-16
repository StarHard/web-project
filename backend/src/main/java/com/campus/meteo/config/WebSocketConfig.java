package com.campus.meteo.config;

import com.campus.meteo.agent.realtime.RealtimeWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置：/ws/realtime 实时数据推送、/ws/alert 告警推送
 * 注意：需与 Nginx 的 WebSocket 升级代理配置配合
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final RealtimeWebSocketHandler realtimeWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(realtimeWebSocketHandler, "/ws/realtime").setAllowedOrigins("*");
    }
}
