package com.model.water.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

@RestController
@RequestMapping("/api")
public class LoginController {
    private final String username;
    private final String password;
    private final SessionTokenService tokens;
    public LoginController(@Value("${admin.username:admin}") String username,
                           @Value("${admin.password:change-me}") String password, SessionTokenService tokens) {
        this.username = username; this.password = password;
        this.tokens = tokens;
    }
    @PostMapping("/login")
    public Map<String,Object> login(@RequestBody LoginRequest request, HttpServletRequest http, HttpServletResponse response) {
        if (!username.equals(request.username()) || !password.equals(request.password()))
            throw new IllegalArgumentException("账号或密码错误");
        String token = tokens.issue();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, http.isSecure(), tokens.ttlSeconds()));
        return Map.of("code","200","msg","ok","data",Map.of("token", token, "username", username));
    }
    @PostMapping("/logout")
    public Map<String,Object> logout(HttpServletRequest request, HttpServletResponse response) {
        tokens.revoke(AuthFilter.bearer(request.getHeader("Authorization")));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", request.isSecure(), 0));
        return Map.of("code", "200", "msg", "ok");
    }
    private static String cookie(String token, boolean secure, long maxAge) {
        return ResponseCookie.from(SessionTokenService.COOKIE_NAME, token).httpOnly(true).secure(secure)
                .sameSite("Strict").path("/ws/realtime").maxAge(maxAge).build().toString();
    }
    public record LoginRequest(String username, String password) {}
}
