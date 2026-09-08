package com.model.water.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class LoginController {
    private final String username;
    private final String password;
    public LoginController(@Value("${admin.username:admin}") String username,
                           @Value("${admin.password:change-me}") String password) {
        this.username = username; this.password = password;
    }
    @PostMapping("/login")
    public Map<String,Object> login(@RequestBody LoginRequest request) {
        if (!username.equals(request.username()) || !password.equals(request.password()))
            throw new IllegalArgumentException("账号或密码错误");
        return Map.of("code","200","msg","ok","data",Map.of("token", UUID.randomUUID().toString(), "username", username));
    }
    public record LoginRequest(String username, String password) {}
}
