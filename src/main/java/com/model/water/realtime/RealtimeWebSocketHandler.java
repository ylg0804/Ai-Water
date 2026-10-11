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
import com.model.water.auth.SessionTokenService;
import com.model.water.auth.SessionHandshakeInterceptor;
import org.springframework.scheduling.annotation.Scheduled;

@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);
    private final ObjectMapper mapper;
    private final SessionTokenService tokens;
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public RealtimeWebSocketHandler(ObjectMapper mapper, SessionTokenService tokens) { this.mapper = mapper; this.tokens = tokens; }

    private boolean authorized(WebSocketSession session) {
        if (session.isOpen() && tokens.valid((String) session.getAttributes().get(SessionHandshakeInterceptor.SESSION_TOKEN))) return true;
        sessions.remove(session);
        try { if (session.isOpen()) session.close(new CloseStatus(4401, "Session expired")); }
        catch (IOException e) { log.debug("关闭失效会话失败", e); }
        return false;
    }

    @Scheduled(fixedDelay = 30000)
    public void closeExpiredSessions() { sessions.forEach(this::authorized); }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) { if (authorized(session)) sessions.add(session); }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { sessions.remove(session); }

    public void publish(String serial, Object measurement, String measuredAt) {
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    "type", "measurement", "serial", serial,
                    "measuredAt", measuredAt, "data", measurement));
            TextMessage message = new TextMessage(payload);
            for (WebSocketSession session : sessions) {
                if (!authorized(session)) continue;
                try { synchronized (session) { session.sendMessage(message); } }
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
                if (!authorized(session)) continue;
                try { synchronized (session) { session.sendMessage(message); } }
                catch (IOException e) { sessions.remove(session); log.warn("WebSocket alarm push failed: {}", session.getId(), e); }
            }
        } catch (Exception e) { log.error("Failed to build realtime alarm payload", e); }
    }

    public void publishRuntime(String serial, Map<String, Object> status) {
        try {
            TextMessage message = new TextMessage(mapper.writeValueAsString(Map.of("type", "runtime", "serial", serial, "data", status)));
            for (WebSocketSession session : sessions) {
                if (!authorized(session)) continue;
                try { synchronized (session) { session.sendMessage(message); } }
                catch (IOException e) { sessions.remove(session); log.warn("运行状态推送失败", e); }
            }
        } catch (Exception e) { log.error("构造运行状态推送失败", e); }
    }
}
