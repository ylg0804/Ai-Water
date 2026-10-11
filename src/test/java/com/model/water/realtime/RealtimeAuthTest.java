package com.model.water.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.model.water.auth.SessionHandshakeInterceptor;
import com.model.water.auth.SessionTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import java.util.Map;
import static org.mockito.Mockito.*;

class RealtimeAuthTest {
    @Test void revokedSessionReceivesNoFurtherEventsAndIsClosed() throws Exception {
        var tokens = new SessionTokenService(3600);
        String token = tokens.issue();
        var session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(SessionHandshakeInterceptor.SESSION_TOKEN, token));
        var handler = new RealtimeWebSocketHandler(new ObjectMapper(), tokens);
        handler.afterConnectionEstablished(session);
        tokens.revoke(token);
        handler.publishRuntime("CAM", Map.of("failureCount", 1));
        verify(session).close(argThat(status -> status.getCode() == 4401));
        verify(session, never()).sendMessage(any());
    }
}
