package com.aitome.account;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;

/**
 * 新设/重置/主动改密的统一密码规则（ACCOUNT-P01，需规修订7）。
 * 按 Unicode 码点计 8–20 位；BCrypt 只使用前 72 字节（Spring Security 6.3.6 不检查长度，超出部分被静默忽略），
 * 所以 UTF-8 超过 72 字节时明确拒绝，绝不截断。不 trim、不限制字符集。登录不套用此规则。
 */
public final class PasswordPolicy {
    public static final int MIN_CODE_POINTS = 8;
    public static final int MAX_CODE_POINTS = 20;
    public static final int MAX_BCRYPT_BYTES = 72;

    private PasswordPolicy() {}

    public static void validateNew(String password, String confirmation) {
        String problem = check(password, confirmation);
        if (problem != null) throw new ApiProblem(HttpStatus.BAD_REQUEST, problem);
    }

    /** 返回第一个问题的提示；通过时返回 null。 */
    public static String check(String password, String confirmation) {
        if (password == null || password.isEmpty()) return "请设置登录密码。";
        int length = password.codePointCount(0, password.length());
        if (length < MIN_CODE_POINTS) return "密码至少需要8位。";
        if (length > MAX_CODE_POINTS) return "密码最多20位。";
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_BYTES) return "密码包含较多特殊字符，请适当缩短。";
        if (!password.equals(confirmation)) return "两次输入的密码不一致。";
        return null;
    }
}
