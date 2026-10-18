package com.aitome.account;

import com.aitome.access.PublicEndpoint;
import com.aitome.common.ApiExceptionHandler.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号邮件流程的公开接口（契约 §4）。令牌就是凭据，所以这些接口不要求登录，但仍要求 CSRF。
 * 申请链接对所有情形返回同一结果；令牌只在请求正文中出现，不进入 URL。
 */
@RestController
@RequestMapping("/api/auth")
@PublicEndpoint("账号邮件流程：令牌即凭据")
public class AuthLinkController {
    private final AccountLinkService links;
    private final AccountPasswordService passwords;

    public AuthLinkController(AccountLinkService links, AccountPasswordService passwords) { this.links = links; this.passwords = passwords; }

    @PostMapping("/request-link")
    public Map<String, Object> requestLink(@RequestBody LinkRequest request, HttpServletRequest http) {
        TokenPurpose purpose = TokenPurpose.fromParam(request.purpose()).filter(p -> p != TokenPurpose.VERIFY)
                .orElseThrow(() -> new ApiProblem(HttpStatus.BAD_REQUEST, "不支持的申请类型"));
        links.requestPublic(purpose, request.email(), http.getRemoteAddr());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", AccountLinkService.UNIFIED_MESSAGE);
        result.put("cooldown", 60); // 公开响应恒为 60，避免暴露邮箱是否处于冷却窗口
        if (purpose == TokenPurpose.SIGNUP) result.put("email", AccountLinkService.normalizeEmail(request.email()));
        return result;
    }

    @PostMapping("/link/inspect")
    public Map<String, Object> inspect(@RequestBody TokenRequest request, HttpServletRequest http) {
        TokenPurpose purpose = TokenPurpose.fromParam(request.purpose()).orElseThrow(() -> new ApiProblem(HttpStatus.GONE, AccountTokenService.MSG_INVALID));
        AccountLinkService.LinkView view = links.inspect(request.token(), purpose, http.getRemoteAddr());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("email", view.email()); result.put("purpose", purpose.param()); result.put("expiresAt", view.expiresAt());
        result.put("verified", view.verified()); result.put("needsVerification", view.needsVerification());
        return result;
    }

    @PostMapping("/signup-verify")
    public Map<String, Object> signupVerify(@RequestBody TokenRequest request, HttpServletRequest http) {
        return Map.of("email", links.confirmSignupEmail(request.token(), http.getRemoteAddr()));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody RegisterRequest request, HttpServletRequest http) {
        String email = links.register(request.token(), request.displayName(), request.password(), request.confirmPassword(), http.getRemoteAddr());
        return Map.of("email", email, "message", "账号创建成功，请使用邮箱和密码登录。"); // 不建立会话：注册后回到正常登录入口
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody TokenRequest request, HttpServletRequest http) {
        boolean legacy = links.verifyEmail(request.token(), http.getRemoteAddr());
        return Map.of("message", "邮箱已验证。", "legacy", legacy);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestBody ResetRequest request, HttpServletRequest http) {
        AccountPasswordService.Result result = passwords.reset(request.token(), request.password(), request.confirmPassword(), http.getRemoteAddr());
        HttpSession session = http.getSession(false);
        if (session != null) session.invalidate(); // 重置后所有设备（含发起重置的这台）都需重新登录
        return Map.of("message", "密码已重置，所有设备已退出，请重新登录。", "noticeFailed", result.noticeFailed());
    }

    public record LinkRequest(String email, String purpose) {}
    public record TokenRequest(String token, String purpose) {}
    public record RegisterRequest(String token, String displayName, String password, String confirmPassword) {}
    public record ResetRequest(String token, String password, String confirmPassword) {}
}
