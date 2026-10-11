package com.model.water.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.model.water.api.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AuthFilter extends OncePerRequestFilter {
    private final SessionTokenService tokens;
    private final ObjectMapper mapper;
    public AuthFilter(SessionTokenService tokens, ObjectMapper mapper) { this.tokens = tokens; this.mapper = mapper; }

    public static String bearer(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/api") || path.startsWith("/api/"))
                || (path.equals("/api/login") && request.getMethod().equals("POST"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!tokens.valid(bearer(request.getHeader("Authorization")))) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(response.getOutputStream(), ApiResponse.error("401", "登录已失效，请重新登录"));
            return;
        }
        chain.doFilter(request, response);
    }
}
