package com.example.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
class UserPasswordController {
    private final AuthService authService;

    UserPasswordController(AuthService authService) {
        this.authService = authService;
    }

    @PatchMapping("/{id}/password")
    Map<String, String> changePassword(
            @PathVariable String id,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest servletRequest) {
        String authorization = servletRequest.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "توكن الدخول غير موجود");
        }
        String authenticatedUserId = authService.authenticatedUserId(
                authorization.substring("Bearer ".length()).trim());
        if (!id.equals(authenticatedUserId)) {
            throw new AuthException(HttpStatus.FORBIDDEN, "مش مسموح لك بتغيير كلمة مرور حساب آخر");
        }
        authService.changePassword(id, request);
        return Map.of("message", "تم تغيير كلمة المرور بنجاح");
    }
}
