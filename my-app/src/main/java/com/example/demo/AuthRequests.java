package com.example.demo;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

record RegisterRequest(
        @NotBlank(message = "الاسم مطلوب") @Size(max = 100) String name,
        @NotBlank @Email(message = "الإيميل غير صحيح") @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "^01[0125][0-9]{8}$",
                message = "رقم الموبايل لازم يكون مصري صحيح (11 رقم)") String phone,
        @NotBlank @Size(min = 8, max = 128,
                message = "الباسورد لازم يكون من 8 إلى 128 حرفًا") String password,
        String servantRole,
        List<String> grades) {}

record LoginRequest(
        @NotBlank @Email(message = "الإيميل غير صحيح") String email,
        @NotBlank(message = "الباسورد مطلوب") String password) {}

record RefreshRequest(String refreshToken) {}

record ForgotPasswordRequest(
        @NotBlank @Size(max = 254) String email) {}

record ResetPasswordRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "^\\d{6}$",
                message = "كود التحقق يجب أن يتكون من 6 أرقام") String code,
        @NotBlank @Size(min = 8, max = 128,
                message = "كلمة المرور يجب أن تكون من 8 إلى 128 حرفًا") String newPassword) {}

record ChangePasswordRequest(
        @NotBlank(message = "يرجى كتابة كلمة السر الحالية") String oldPassword,
        @NotBlank @Size(min = 8, max = 128,
                message = "كلمة السر الجديدة 8 أحرف على الأقل") String newPassword) {}
