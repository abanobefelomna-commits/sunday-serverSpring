package com.example.demo;

import org.springframework.http.HttpStatus;

class AuthException extends RuntimeException {
    private final HttpStatus status;

    AuthException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    HttpStatus status() {
        return status;
    }
}
