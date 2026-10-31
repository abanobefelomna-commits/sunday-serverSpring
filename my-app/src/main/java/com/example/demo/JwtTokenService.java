package com.example.demo;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class JwtTokenService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long expiresSeconds;

    JwtTokenService(
            ObjectMapper objectMapper,
            @Value("${auth.jwt.secret}") String secret,
            @Value("${auth.jwt.expires-seconds}") long expiresSeconds) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        if (expiresSeconds < 1) {
            throw new IllegalStateException("JWT_EXPIRES_SECONDS must be positive");
        }
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expiresSeconds = expiresSeconds;
    }

    String issue(String userId, String role) {
        Instant now = Instant.now();
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = Map.of(
                "sub", userId,
                "role", role,
                "iat", now.getEpochSecond(),
                "exp", now.plusSeconds(expiresSeconds).getEpochSecond());
        try {
            String unsigned = encode(header) + "." + encode(payload);
            return unsigned + "." + ENCODER.encodeToString(sign(unsigned));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not issue access token", exception);
        }
    }

    Map<String, Object> verify(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                throw unauthorized();
            }
            Map<String, Object> header = objectMapper.readValue(
                    DECODER.decode(parts[0]), new TypeReference<>() {});
            if (!"HS256".equals(header.get("alg"))) {
                throw unauthorized();
            }
            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] actual = DECODER.decode(parts[2]);
            if (!java.security.MessageDigest.isEqual(expected, actual)) {
                throw unauthorized();
            }
            Map<String, Object> payload = objectMapper.readValue(
                    DECODER.decode(parts[1]), new TypeReference<>() {});
            Object expiration = payload.get("exp");
            if (!(expiration instanceof Number number)
                    || number.longValue() <= Instant.now().getEpochSecond()
                    || !(payload.get("sub") instanceof String)
                    || !(payload.get("role") instanceof String)) {
                throw unauthorized();
            }
            return payload;
        } catch (AuthException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unauthorized();
        }
    }

    private String encode(Map<String, Object> values) throws Exception {
        return ENCODER.encodeToString(objectMapper.writeValueAsBytes(values));
    }

    private byte[] sign(String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
    }

    private AuthException unauthorized() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "توكن الدخول غير صالح أو منتهي");
    }
}
