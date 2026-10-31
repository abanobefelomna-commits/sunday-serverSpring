package com.example.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
class AuthRateLimitInterceptor implements HandlerInterceptor {
    private static final long WINDOW_MILLIS = 60_000;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong requestCount = new AtomicLong();

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        int limit = limitFor(path);
        String key = request.getRemoteAddr() + ":" + path;
        long now = System.currentTimeMillis();
        Window window = windows.compute(key, (ignored, current) ->
                current == null || current.expiresAt <= now
                        ? new Window(now + WINDOW_MILLIS)
                        : current);

        synchronized (window) {
            if (window.count >= limit) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(
                        "{\"message\":\"طلبات كثيرة، حاول مرة أخرى لاحقًا\"}");
                return false;
            }
            window.count++;
        }

        if (requestCount.incrementAndGet() % 100 == 0) {
            windows.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        }
        return true;
    }

    private int limitFor(String path) {
        return switch (path) {
            case "/api/auth/register", "/api/auth/login", "/api/auth/reset-password" -> 5;
            case "/api/auth/refresh" -> 10;
            case "/api/auth/forgot-password" -> 3;
            default -> 100;
        };
    }

    private static final class Window {
        private final long expiresAt;
        private int count;

        private Window(long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }
}
