package com.minerva.aiservice.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.minerva.aiservice.config.AiServiceProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ServiceKeyFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-AI-Service-Key";
    private static final String HEALTH_PATH = "/health";

    private final AiServiceProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) && HEALTH_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String providedKey = request.getHeader(HEADER_NAME);
        if (providedKey == null || providedKey.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing AI service key");
            return;
        }

        byte[] expected = properties.security().apiKey().getBytes(StandardCharsets.UTF_8);
        byte[] provided = providedKey.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, provided)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid AI service key");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
