package com.example.demo;

import java.time.Instant;
import java.util.List;

record UserProfile(
        String id,
        String name,
        String email,
        boolean emailVerified,
        String phone,
        String role,
        List<String> grades,
        String avatar,
        Instant createdAt,
        Instant updatedAt) {}

record AuthResponse(String accessToken, UserProfile user) {}

record RefreshSession(AuthResponse response, String token, Instant expiresAt) {}
