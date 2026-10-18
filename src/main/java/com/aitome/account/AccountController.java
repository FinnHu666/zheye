package com.aitome.account;

import com.aitome.access.AccessContext;
import com.aitome.access.AccessService;
import com.aitome.access.Permission;
import com.aitome.access.RequirePermission;
import com.aitome.user.AuthController;
import com.aitome.user.UserAccount;
import com.aitome.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录态账号接口（契约 §5）。profile.manage 对所有角色锁定拥有，因此任何角色都能管理自己的密码。
 * 归属一律取自会话，请求中的 userId / email 被忽略。
 */
@RestController
@RequestMapping("/api/account")
@RequirePermission(Permission.PROFILE_MANAGE)
public class AccountController {
    private final UserRepository users;
    private final AccessService access;
    private final AccountLinkService links;
    private final AccountPasswordService passwords;
    private final AccountTokenService tokens;

    public AccountController(UserRepository users, AccessService access, AccountLinkService links, AccountPasswordService passwords, AccountTokenService tokens) {
        this.users = users; this.access = access; this.links = links; this.passwords = passwords; this.tokens = tokens;
    }

    @GetMapping("/security")
    public Map<String, Object> security(HttpSession session) {
        UserAccount user = AuthController.requireUser(session, users);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId()); result.put("email", user.getEmail()); result.put("displayName", user.getDisplayName());
        AccessContext context = access.forUser(user.getId());
        if (context != null) {
            result.put("role", Map.of("code", context.roleCode(), "name", context.roleName()));
            result.put("permissions", context.permissions().stream().map(Permission::code).toList());
        }
        result.put("emailVerified", user.isEmailVerified());
        result.put("resetCooldown", tokens.cooldownSeconds(TokenPurpose.RESET, user.getEmail()));
        result.put("verifyCooldown", tokens.cooldownSeconds(TokenPurpose.VERIFY, user.getEmail()));
        return result;
    }

    @PostMapping("/password")
    public Map<String, Object> changePassword(@RequestBody PasswordRequest request, HttpSession session, HttpServletRequest http) {
        UserAccount user = AuthController.requireUser(session, users);
        AccountPasswordService.Result result = passwords.change(user.getId(), request.oldPassword(), request.password(), request.confirmPassword());
        http.changeSessionId(); // 保留当前设备：换新的会话 ID，并采用新的撤销版本；其他设备的旧版本随之失效
        session.setAttribute(AuthController.AUTH_EPOCH, result.newEpoch());
        return Map.of("message", "密码已更新，当前设备保持登录，其他设备已退出。", "noticeFailed", result.noticeFailed());
    }

    @PostMapping("/password-reset-link")
    public Map<String, Object> passwordResetLink(HttpSession session) {
        UserAccount user = AuthController.requireUser(session, users);
        int cooldown = links.requestForUser(user, TokenPurpose.RESET);
        return Map.of("message", AccountLinkService.UNIFIED_MESSAGE, "cooldown", cooldown > 0 ? cooldown : 60);
    }

    @PostMapping("/verification-link")
    public Map<String, Object> verificationLink(HttpSession session) {
        UserAccount user = AuthController.requireUser(session, users);
        int cooldown = links.requestForUser(user, TokenPurpose.VERIFY);
        return Map.of("message", AccountLinkService.UNIFIED_MESSAGE, "cooldown", cooldown > 0 ? cooldown : 60);
    }

    public record PasswordRequest(String oldPassword, String password, String confirmPassword) {}
}
