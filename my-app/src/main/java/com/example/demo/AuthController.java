package com.example.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private static final String REFRESH_COOKIE = "refresh_token";
    private final AuthService authService;
    private final boolean production;

    AuthController(
            AuthService authService,
            @Value("${NODE_ENV:development}") String environment) {
        this.authService = authService;
        this.production = "production".equals(environment);
    }

    @PostMapping("/register")
    AuthResponse register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletResponse response) {
        RefreshSession session = authService.register(request);
        setRefreshCookie(response, session);
        return session.response();
    }

    @PostMapping("/login")
    AuthResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        RefreshSession session = authService.login(request);
        setRefreshCookie(response, session);
        return session.response();
    }

    @PostMapping("/refresh")
    AuthResponse refresh(
            HttpServletRequest request,
            @Valid @RequestBody(required = false) RefreshRequest body,
            HttpServletResponse response) {
        String token = cookieValue(request, REFRESH_COOKIE);
        if ((token == null || token.isBlank()) && body != null) {
            token = body.refreshToken();
        }
        if (token == null || token.isBlank()) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "توكن التجديد غير موجود");
        }
        RefreshSession session = authService.refresh(token);
        setRefreshCookie(response, session);
        return session.response();
    }

    @PostMapping("/forgot-password")
    Map<String, String> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
        return Map.of("message",
                "إذا كان الحساب مسجلًا، فسيصلك كود استعادة كلمة المرور على بريده الإلكتروني.");
    }

    @PostMapping("/reset-password")
    Map<String, String> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return Map.of("message", "تم تغيير كلمة المرور بنجاح. سجّل الدخول مرة أخرى.");
    }

    @PostMapping("/logout")
    Map<String, String> logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        authService.logout(cookieValue(request, REFRESH_COOKIE));
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(production)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build()
                .toString());
        return Map.of("message", "تم تسجيل الخروج");
    }

    @GetMapping("/me")
    UserProfile me(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "توكن الدخول غير موجود");
        }
        return authService.me(authorization.substring("Bearer ".length()).trim());
    }

    private void setRefreshCookie(HttpServletResponse response, RefreshSession session) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(
                        REFRESH_COOKIE, session.token())
                .httpOnly(true)
                .secure(production)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(Duration.between(java.time.Instant.now(), session.expiresAt()))
                .build()
                .toString());
    }

    private String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
