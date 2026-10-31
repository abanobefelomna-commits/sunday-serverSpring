package com.example.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
class AuthService {
    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> VALID_GRADES =
            Set.of("first", "second", "third", "fourth", "fifth", "sixth", "general");
    private static final Map<String, String> SERVANT_ROLES = Map.of(
            "servant", "خادم",
            "class_leader", "قائد فصل",
            "admin", "مسؤول",
            "priest", "أب كاهن");
    private static final RowMapper<UserRow> USER_ROW_MAPPER = (rs, rowNum) -> {
        java.sql.Array gradeArray = rs.getArray("grades");
        List<String> grades = new ArrayList<>();
        if (gradeArray != null) {
            Object[] values = (Object[]) gradeArray.getArray();
            for (Object value : values) {
                grades.add(value.toString());
            }
            gradeArray.free();
        }
        return new UserRow(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getBoolean("emailVerified"),
                rs.getString("phone"),
                rs.getString("password"),
                rs.getString("role"),
                grades,
                rs.getString("avatar"),
                rs.getTimestamp("createdAt").toInstant(),
                rs.getTimestamp("updatedAt").toInstant());
    };

    private final JdbcTemplate jdbc;
    private final PasswordService passwords;
    private final JwtTokenService jwtTokens;
    private final JavaMailSender mailSender;
    private final String otpSecret;
    private final String mailFrom;
    private final int refreshTtlDays;
    private final int otpTtlMinutes;
    private final int otpMaxAttempts;

    AuthService(
            JdbcTemplate jdbc,
            PasswordService passwords,
            JwtTokenService jwtTokens,
            JavaMailSender mailSender,
            @Value("${auth.otp.secret}") String otpSecret,
            @Value("${auth.mail.from}") String mailFrom,
            @Value("${SMTP_USER:}") String smtpUser,
            @Value("${auth.refresh.ttl-days}") int refreshTtlDays,
            @Value("${auth.otp.ttl-minutes}") int otpTtlMinutes,
            @Value("${auth.otp.max-attempts}") int otpMaxAttempts) {
        if (otpSecret == null || otpSecret.length() < 32) {
            throw new IllegalStateException("OTP_SECRET must contain at least 32 characters");
        }
        if (refreshTtlDays < 1 || otpTtlMinutes < 1 || otpTtlMinutes > 30
                || otpMaxAttempts < 1 || otpMaxAttempts > 10) {
            throw new IllegalStateException("Invalid authentication expiry or attempt limits");
        }
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.jwtTokens = jwtTokens;
        this.mailSender = mailSender;
        this.otpSecret = otpSecret;
        this.mailFrom = mailFrom.isBlank() ? smtpUser : mailFrom;
        this.refreshTtlDays = refreshTtlDays;
        this.otpTtlMinutes = otpTtlMinutes;
        this.otpMaxAttempts = otpMaxAttempts;
    }

    @Transactional
    RefreshSession register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        String phone = request.phone().trim();
        if (findByEmailOrPhone(email, phone) != null) {
            throw new AuthException(HttpStatus.CONFLICT, "الإيميل أو رقم التليفون مستخدم بالفعل");
        }
        List<String> requestedGrades = request.grades() == null ? List.of() : request.grades();
        if (requestedGrades.stream().anyMatch(grade -> !VALID_GRADES.contains(grade))
                || requestedGrades.size() != requestedGrades.stream().distinct().count()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "الفصول المختارة غير صالحة");
        }
        String servantRole = request.servantRole() == null ? "servant" : request.servantRole();
        if (!SERVANT_ROLES.containsKey(servantRole)) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "الدور المختار غير صالح");
        }
        List<String> grades = Set.of("admin", "priest").contains(servantRole)
                ? List.of()
                : requestedGrades;
        List<String> assignedGrades = grades;
        String userId;
        try {
            userId = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection -> {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO users (name, email, phone, password, role, grades)
                        VALUES (?, ?, ?, ?, 'servant', ?::users_grades_enum[])
                        RETURNING id
                        """)) {
                    statement.setString(1, request.name().trim());
                    statement.setString(2, email);
                    statement.setString(3, phone);
                    statement.setString(4, passwords.hash(request.password()));
                    statement.setArray(5, connection.createArrayOf(
                            "users_grades_enum", grades.toArray()));
                    try (var result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalStateException("User insert returned no identifier");
                        }
                        return result.getString(1);
                    }
                }
            });
        } catch (DuplicateKeyException exception) {
            throw new AuthException(HttpStatus.CONFLICT, "الإيميل أو رقم التليفون مستخدم بالفعل");
        }
        UserRow user = findById(userId);
        syncServant(user, servantRole, assignedGrades);
        return startSession(user);
    }

    @Transactional
    RefreshSession login(LoginRequest request) {
        UserRow user = jdbc.query(
                "SELECT * FROM users WHERE email = ?",
                USER_ROW_MAPPER,
                request.email().trim().toLowerCase()).stream().findFirst().orElse(null);
        if (user == null || !passwords.matches(request.password(), user.password())) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "الإيميل أو الباسورد غلط");
        }
        if (passwords.needsUpgrade(user.password())) {
            jdbc.update("UPDATE users SET password = ?, \"updatedAt\" = now() WHERE id = ?",
                    passwords.hash(request.password()), user.id());
            user = findById(user.id());
        }
        return startSession(user);
    }

    @Transactional
    RefreshSession refresh(String token) {
        String hash = sha256(token);
        List<String> users = jdbc.query(
                """
                SELECT "userId" FROM refresh_tokens
                WHERE "tokenHash" = ? AND "revokedAt" IS NULL AND "expiresAt" > now()
                FOR UPDATE
                """,
                (rs, rowNum) -> rs.getString(1),
                hash);
        if (users.isEmpty() || jdbc.update(
                """
                UPDATE refresh_tokens SET "revokedAt" = now()
                WHERE "tokenHash" = ? AND "revokedAt" IS NULL
                """,
                hash) != 1) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "جلسة الدخول منتهية أو غير صالحة");
        }
        UserRow user = findById(users.getFirst());
        if (user == null) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "المستخدم غير موجود");
        }
        return startSession(user);
    }

    @Transactional
    void logout(String token) {
        if (token != null && !token.isBlank()) {
            jdbc.update(
                    """
                    UPDATE refresh_tokens SET "revokedAt" = now()
                    WHERE "tokenHash" = ? AND "revokedAt" IS NULL
                    """,
                    sha256(token));
        }
    }

    @Transactional
    void forgotPassword(String identifier) {
        UserRow user = findByIdentifier(identifier.trim().toLowerCase());
        if (user == null) {
            return;
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("""
                UPDATE auth_otp_codes
                SET "consumedAt" = now()
                WHERE "userId" = ? AND purpose = 'password_reset' AND "consumedAt" IS NULL
                """, user.id());
        jdbc.update("""
                INSERT INTO auth_otp_codes ("userId", purpose, "codeHash", "expiresAt")
                VALUES (?, 'password_reset', ?, now() + (? * interval '1 minute'))
                """, user.id(), hashOtp(code), otpTtlMinutes);
        try {
            if (mailFrom.isBlank()) {
                throw new IllegalStateException("MAIL_FROM or SMTP_USER must be configured");
            }
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(user.email());
            message.setSubject("استعادة كلمة المرور - Sunday School");
            message.setText("كود استعادة كلمة المرور هو: " + code
                    + "\nصلاحية الكود " + otpTtlMinutes + " دقائق.");
            mailSender.send(message);
        } catch (RuntimeException exception) {
            jdbc.update("""
                    UPDATE auth_otp_codes
                    SET "consumedAt" = now()
                    WHERE "userId" = ? AND purpose = 'password_reset' AND "consumedAt" IS NULL
                    """, user.id());
            logger.warn("Could not send password recovery email for user {}", user.id(), exception);
        }
    }

    @Transactional(noRollbackFor = AuthException.class)
    void resetPassword(ResetPasswordRequest request) {
        UserRow user = findByIdentifier(request.email().trim().toLowerCase());
        if (user == null) {
            throw invalidOtp();
        }
        List<OtpRow> codes = jdbc.query("""
                SELECT id, "codeHash", attempts, "expiresAt"
                FROM auth_otp_codes
                WHERE "userId" = ? AND purpose = 'password_reset'
                  AND "consumedAt" IS NULL
                ORDER BY "createdAt" DESC
                LIMIT 1
                FOR UPDATE
                """, (rs, rowNum) -> new OtpRow(
                rs.getString("id"),
                rs.getString("codeHash"),
                rs.getInt("attempts"),
                rs.getTimestamp("expiresAt").toInstant()), user.id());
        if (codes.isEmpty()) {
            throw invalidOtp();
        }
        OtpRow otp = codes.getFirst();
        if (!otp.expiresAt().isAfter(Instant.now())) {
            throw invalidOtp();
        }
        if (otp.attempts() >= otpMaxAttempts) {
            throw new AuthException(HttpStatus.UNAUTHORIZED,
                    "تم استنفاد محاولات التحقق، اطلب كودًا جديدًا");
        }
        if (!MessageDigest.isEqual(
                otp.codeHash().getBytes(StandardCharsets.US_ASCII),
                hashOtp(request.code()).getBytes(StandardCharsets.US_ASCII))) {
            jdbc.update("UPDATE auth_otp_codes SET attempts = attempts + 1 WHERE id = ?", otp.id());
            if (otp.attempts() + 1 >= otpMaxAttempts) {
                throw new AuthException(HttpStatus.UNAUTHORIZED,
                        "تم استنفاد محاولات التحقق، اطلب كودًا جديدًا");
            }
            throw new AuthException(HttpStatus.BAD_REQUEST, "كود التحقق غير صحيح");
        }
        jdbc.update("UPDATE auth_otp_codes SET \"consumedAt\" = now() WHERE id = ?", otp.id());
        jdbc.update("UPDATE users SET password = ?, \"updatedAt\" = now() WHERE id = ?",
                passwords.hash(request.newPassword()), user.id());
        jdbc.update("""
                UPDATE refresh_tokens SET "revokedAt" = now()
                WHERE "userId" = ? AND "revokedAt" IS NULL
                """, user.id());
    }

    @Transactional
    void changePassword(String userId, ChangePasswordRequest request) {
        UserRow user = jdbc.query(
                "SELECT * FROM users WHERE id = ?",
                USER_ROW_MAPPER,
                userId).stream().findFirst().orElse(null);
        if (user == null || !passwords.matches(request.oldPassword(), user.password())) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "كلمة المرور الحالية غير صحيحة");
        }
        jdbc.update("UPDATE users SET password = ?, \"updatedAt\" = now() WHERE id = ?",
                passwords.hash(request.newPassword()), userId);
        jdbc.update("""
                UPDATE refresh_tokens SET "revokedAt" = now()
                WHERE "userId" = ? AND "revokedAt" IS NULL
                """, userId);
    }

    String authenticatedUserId(String accessToken) {
        return (String) jwtTokens.verify(accessToken).get("sub");
    }

    Map<String, Object> authenticatedClaims(String accessToken) {
        return jwtTokens.verify(accessToken);
    }

    UserProfile me(String accessToken) {
        Map<String, Object> payload = jwtTokens.verify(accessToken);
        UserRow user = findById((String) payload.get("sub"));
        if (user == null) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "المستخدم غير موجود");
        }
        return user.profile();
    }

    private RefreshSession startSession(UserRow user) {
        String token = randomToken();
        Instant expiresAt = Instant.now().plusSeconds(refreshTtlDays * 86400L);
        jdbc.update("""
                INSERT INTO refresh_tokens ("tokenHash", "userId", "expiresAt")
                VALUES (?, ?, ?)
                """, sha256(token), user.id(), java.sql.Timestamp.from(expiresAt));
        return new RefreshSession(
                new AuthResponse(jwtTokens.issue(user.id(), user.role()), user.profile()),
                token,
                expiresAt);
    }

    private UserRow findById(String id) {
        return jdbc.query("SELECT * FROM users WHERE id = ?", USER_ROW_MAPPER, id)
                .stream().findFirst().orElse(null);
    }

    private UserRow findByEmailOrPhone(String email, String phone) {
        return jdbc.query(
                "SELECT * FROM users WHERE email = ? OR phone = ? LIMIT 1",
                USER_ROW_MAPPER, email, phone).stream().findFirst().orElse(null);
    }

    private UserRow findByIdentifier(String identifier) {
        return jdbc.query(
                "SELECT * FROM users WHERE email = ? OR phone = ? LIMIT 1",
                USER_ROW_MAPPER, identifier, identifier).stream().findFirst().orElse(null);
    }

    private void syncServant(UserRow user, String servantRole, List<String> grades) {
        String label = SERVANT_ROLES.get(servantRole);
        List<String> servantIds = jdbc.query(
                "SELECT id FROM servants WHERE phone = ? OR email = ? LIMIT 1",
                (rs, rowNum) -> rs.getString(1), user.phone(), user.email());
        String servantId;
        if (servantIds.isEmpty()) {
            servantId = jdbc.queryForObject("""
                    INSERT INTO servants (name, phone, email, role, avatar)
                    VALUES (?, ?, ?, ?::servants_role_enum, ?)
                    RETURNING id
                    """, String.class, user.name(), user.phone(), user.email(), label, user.avatar());
        } else {
            servantId = servantIds.getFirst();
            jdbc.update("""
                    UPDATE servants SET name = ?, phone = ?, email = ?, role = ?::servants_role_enum,
                        avatar = ?, "updatedAt" = now()
                    WHERE id = ?
                    """, user.name(), user.phone(), user.email(), label, user.avatar(), servantId);
            jdbc.update("DELETE FROM servants_classes WHERE \"servantsId\" = ?", servantId);
        }
        if (!grades.isEmpty()) {
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO servants_classes ("servantsId", "classesId")
                        SELECT ?, id FROM classes WHERE grade = ANY (?::classes_grade_enum[])
                        ON CONFLICT DO NOTHING
                        """)) {
                    statement.setString(1, servantId);
                    statement.setArray(2, connection.createArrayOf(
                            "classes_grade_enum", grades.toArray()));
                    statement.executeUpdate();
                }
                return null;
            });
        }
    }

    private String hashOtp(String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(otpSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash password reset code", exception);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private AuthException invalidOtp() {
        return new AuthException(HttpStatus.BAD_REQUEST, "الكود غير صحيح أو انتهت صلاحيته");
    }

    private record OtpRow(String id, String codeHash, int attempts, Instant expiresAt) {}

    private record UserRow(
            String id,
            String name,
            String email,
            boolean emailVerified,
            String phone,
            String password,
            String role,
            List<String> grades,
            String avatar,
            Instant createdAt,
            Instant updatedAt) {
        UserProfile profile() {
            return new UserProfile(
                    id, name, email, emailVerified, phone, role, grades, avatar, createdAt, updatedAt);
        }
    }
}
