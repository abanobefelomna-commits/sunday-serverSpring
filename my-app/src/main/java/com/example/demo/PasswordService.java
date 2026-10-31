package com.example.demo;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
class PasswordService {
    private final Argon2PasswordEncoder argon2 =
            new Argon2PasswordEncoder(16, 32, 4, 65536, 3);
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    String hash(String password) {
        return argon2.encode(password);
    }

    boolean matches(String password, String encoded) {
        try {
            if (encoded.startsWith("$argon2")) {
                return argon2.matches(password, normalizeArgonParameters(encoded));
            }
            return bcrypt.matches(password, encoded);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    boolean needsUpgrade(String encoded) {
        return !encoded.startsWith("$argon2");
    }

    private String normalizeArgonParameters(String encoded) {
        return encoded.replaceFirst(
                "(m=\\d+),p=(\\d+),t=(\\d+)",
                "$1,t=$3,p=$2");
    }
}
