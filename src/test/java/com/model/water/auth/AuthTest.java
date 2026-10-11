package com.model.water.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.model.water.api.GlobalExceptionHandler;
import com.model.water.device.DeviceController;
import com.model.water.device.DeviceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthTest {
    final ObjectMapper mapper = new ObjectMapper();
    final SessionTokenService tokens = new SessionTokenService(3600);
    final DeviceRepository devices = mock(DeviceRepository.class);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LoginController("admin", "test-pass", tokens), new DeviceController(devices))
            .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new AuthFilter(tokens, mapper)).build();

    @Test void protectsEveryApiIncludingDownloadsAndRejectsForgedTokens() throws Exception {
        for (String path : List.of("/api/device", "/api/alarm", "/api/alarm/1/snapshot", "/api/measurement/export", "/api/ezviz/measurements/latest")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("401"));
            mvc.perform(get(path).header("Authorization", "Bearer forged")).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/device").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
    @Test void loginIssuesHttpOnlyCookieAndLogoutRevokesAccess() throws Exception {
        var response = mvc.perform(post("/api/login").contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"test-pass\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String token = mapper.readTree(response.getContentAsString()).path("data").path("token").asText();
        assertTrue(tokens.valid(token));
        assertTrue(response.getHeader("Set-Cookie").contains("HttpOnly"));
        assertTrue(response.getHeader("Set-Cookie").contains("SameSite=Strict"));
        mvc.perform(get("/api/device").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/api/logout").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/device").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/login").contentType("application/json").content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void expiresAtTheExactDeadline() {
        Clock clock = mock(Clock.class);
        Instant start = Instant.parse("2026-10-08T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        SessionTokenService sessions = new SessionTokenService(10, clock);
        String token = sessions.issue();
        when(clock.instant()).thenReturn(start.plusSeconds(9)); assertTrue(sessions.valid(token));
        when(clock.instant()).thenReturn(start.plusSeconds(10)); assertFalse(sessions.valid(token));
    }
    @Test void websocketRequiresAValidSessionCookieAndRejectsQueryTokens() {
        var interceptor = new SessionHandshakeInterceptor(tokens);
        var request = new MockHttpServletRequest("GET", "/ws/realtime");
        request.setQueryString("token=" + tokens.issue());
        var response = new MockHttpServletResponse();
        assertFalse(interceptor.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(response), null, new HashMap<>()));
        assertEquals(401, response.getStatus());
        String token = tokens.issue(); request.setCookies(new Cookie(SessionTokenService.COOKIE_NAME, token));
        Map<String,Object> attributes = new HashMap<>();
        assertTrue(interceptor.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(new MockHttpServletResponse()), null, attributes));
        assertEquals(token, attributes.get(SessionHandshakeInterceptor.SESSION_TOKEN));
        tokens.revoke(token);
        assertFalse(interceptor.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(new MockHttpServletResponse()), null, new HashMap<>()));
    }
}
