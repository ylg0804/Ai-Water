package com.model.water.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);
    private final ObjectMapper mapper;
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public RealtimeWebSocketHandler(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) { sessions.add(session); }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { sessions.remove(session); }

    public void publish(String serial, Object measurement, String measuredAt) {
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    "type", "measurement", "serial", serial,
                    "measuredAt", measuredAt, "data", measurement));
            TextMessage message = new TextMessage(payload);
            for (WebSocketSession session : sessions) {
                if (!session.isOpen()) { sessions.remove(session); continue; }
                try { session.sendMessage(message); }
                catch (IOException e) { sessions.remove(session); log.warn("WebSocket 推送失败: {}", session.getId(), e); }
            }
        } catch (Exception e) { log.error("构造实时测量推送失败", e); }
    }

    public void publishAlarm(String serial, String type, String detail, String snapshotPath, String createdAt) {
        try {
            String payload = mapper.writeValueAsString(Map.of("type", "alarm", "serial", serial,
                    "alarmType", type, "detail", detail == null ? "" : detail,
                    "snapshotPath", snapshotPath == null ? "" : snapshotPath, "createdAt", createdAt));
            TextMessage message = new TextMessage(payload);
            for (WebSocketSession session : sessions) {
                if (!session.isOpen()) { sessions.remove(session); continue; }
                try { session.sendMessage(message); }
                catch (IOException e) { sessions.remove(session); log.warn("WebSocket alarm push failed: {}", session.getId(), e); }
            }
        } catch (Exception e) { log.error("Failed to build realtime alarm payload", e); }
    }
}
