package com.model.water.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class SessionHandshakeInterceptor implements HandshakeInterceptor {
    public static final String SESSION_TOKEN = "sessionToken";
    private final SessionTokenService tokens;
    public SessionHandshakeInterceptor(SessionTokenService tokens) { this.tokens = tokens; }
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String token = AuthFilter.bearer(request.getHeaders().getFirst("Authorization"));
        if (token == null && request instanceof ServletServerHttpRequest servlet) {
            var cookies = servlet.getServletRequest().getCookies();
            if (cookies != null) for (var cookie : cookies)
                if (SessionTokenService.COOKIE_NAME.equals(cookie.getName())) token = cookie.getValue();
        }
        if (!tokens.valid(token)) { response.setStatusCode(HttpStatus.UNAUTHORIZED); return false; }
        attributes.put(SESSION_TOKEN, token);
        return true;
    }
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) { }
}
