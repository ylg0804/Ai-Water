package com.model.water.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Single-instance sessions; only token hashes are retained on the server. */
@Service
public class SessionTokenService {
    public static final String COOKIE_NAME = "water_session";
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final long ttlSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public SessionTokenService(@Value("${admin.session-ttl-seconds:28800}") long ttlSeconds) {
        this(ttlSeconds, Clock.systemUTC());
    }

    SessionTokenService(long ttlSeconds, Clock clock) {
        if (ttlSeconds < 1) throw new IllegalArgumentException("会话有效期必须大于 0");
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    public String issue() {
        cleanup();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(hash(token), clock.instant().plusSeconds(ttlSeconds));
        return token;
    }

    public boolean valid(String token) {
        if (token == null || token.length() != 43) return false;
        String key = hash(token);
        Instant expires = sessions.get(key);
        if (expires == null) return false;
        if (!clock.instant().isBefore(expires)) { sessions.remove(key, expires); return false; }
        return true;
    }

    public void revoke(String token) { if (token != null) sessions.remove(hash(token)); }
    public long ttlSeconds() { return ttlSeconds; }

    @Scheduled(fixedDelay = 60000)
    public void cleanup() { sessions.entrySet().removeIf(e -> !clock.instant().isBefore(e.getValue())); }

    private static String hash(String token) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
