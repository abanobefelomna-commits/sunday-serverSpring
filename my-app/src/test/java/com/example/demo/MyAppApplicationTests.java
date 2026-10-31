package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.sql.init.mode=never",
		"app.seed-default-classes=false",
		"auth.jwt.secret=test-jwt-secret-that-is-long-enough-for-hs256",
		"auth.otp.secret=test-otp-secret-that-is-long-enough-for-hmac"
})
class MyAppApplicationTests {

	@Test
	void contextLoads() {
	}

}
