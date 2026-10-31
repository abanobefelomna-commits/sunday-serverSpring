package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AuthContractTests {
    private static final String SECRET = "test-jwt-secret-that-is-long-enough-for-hs256";

    @Test
    void accessTokenUsesSharedJwtClaimsAndHs256Header() throws Exception {
        JwtTokenService tokens = new JwtTokenService(new ObjectMapper(), SECRET, 3600);

        String token = tokens.issue("user-123", "servant");
        var claims = tokens.verify(token);
        var header = new ObjectMapper().readValue(
                Base64.getUrlDecoder().decode(token.split("\\.")[0]),
                java.util.Map.class);

        assertEquals("HS256", header.get("alg"));
        assertEquals("user-123", claims.get("sub"));
        assertEquals("servant", claims.get("role"));
        assertTrue(claims.containsKey("exp"));
    }

    @Test
    void accessTokenRejectsTampering() {
        JwtTokenService tokens = new JwtTokenService(new ObjectMapper(), SECRET, 3600);
        String token = tokens.issue("user-123", "servant");
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");

        assertThrows(AuthException.class, () -> tokens.verify(tampered));
    }

    @Test
    void passwordsAreStoredAsArgon2AndLegacyBcryptCanBeVerified() {
        PasswordService passwords = new PasswordService();
        String argonHash = passwords.hash("Correct-Horse-123");
        String nodeArgonHash =
                "$argon2id$v=19$m=65536,p=4,t=3$liZlS+EuKVpXHhPve9E3Sw$hXJ0KHijzz7Q2AdKlwl2thyS2hF8cnKjTLsiKUKc0yY";
        String nodeBcryptHash =
                "$2b$10$SHjgQrbi.gyWSp8cF88WCu58lGuRQzYy/GOmYNY/3b8DLzxkotz.W";

        assertTrue(argonHash.startsWith("$argon2id$"));
        assertTrue(passwords.matches("Correct-Horse-123", argonHash));
        assertTrue(passwords.matches("Cross-runtime-test-password", nodeArgonHash));
        assertFalse(passwords.needsUpgrade(nodeArgonHash));
        assertTrue(passwords.needsUpgrade(nodeBcryptHash));
        assertTrue(passwords.matches("Cross-runtime-test-password", nodeBcryptHash));
        assertFalse(passwords.matches("wrong-password", argonHash));
    }
}
