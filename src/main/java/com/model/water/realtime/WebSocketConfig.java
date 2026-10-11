package com.model.water.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import com.model.water.auth.SessionHandshakeInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final RealtimeWebSocketHandler handler;
    private final SessionHandshakeInterceptor auth;
    private final String[] origins;

    public WebSocketConfig(RealtimeWebSocketHandler handler, SessionHandshakeInterceptor auth,
                          @Value("${waterflow.websocket-origins:http://127.0.0.1:5173,http://localhost:5173,http://127.0.0.1:8080,http://localhost:8080}") String[] origins) {
        this.handler = handler; this.auth = auth; this.origins = origins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/realtime").addInterceptors(auth).setAllowedOrigins(origins);
    }
}
