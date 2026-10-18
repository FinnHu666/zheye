package com.aitome.user;

import com.aitome.access.AccessContext;
import com.aitome.access.AccessService;
import com.aitome.access.Permission;
import com.aitome.access.PublicEndpoint;
import com.aitome.common.ApiExceptionHandler.ApiProblem;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@PublicEndpoint("登录流程；/me 自行要求登录")
public class AuthController {
    public static final String USER_ID = "USER_ID";
    /** 登录时记录的会话撤销版本；与 user_accounts.session_epoch 不一致即视为已失效（FINNS-15）。 */
    public static final String AUTH_EPOCH = "AUTH_EPOCH";
    private final UserRepository users;
    private final BCryptPasswordEncoder encoder;
    private final CookieCsrfTokenRepository csrfTokens;
    private final AccessService access;

    public AuthController(UserRepository users, BCryptPasswordEncoder encoder, CookieCsrfTokenRepository csrfTokens, AccessService access) {
        this.users = users; this.encoder = encoder; this.csrfTokens = csrfTokens; this.access = access;
        this.dummyHash = encoder.encode("finns-timing-equalizer");
    }

    /** 邮箱不存在时也做一次 BCrypt 比对，使耗时与“密码错误”接近。 */
    private final String dummyHash;

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request, HttpSession session, HttpServletRequest servletRequest, HttpServletResponse response) {
        UserAccount user = users.findByEmailIgnoreCase(request.email()).orElse(null);
        boolean matches = encoder.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确");
        // 验证门槛只在密码正确之后提示，避免未授权者借此探测邮箱状态；旧账号未验证仍可登录并被提示补验证
        if (!user.isEmailVerified() && !user.isLegacyAccount()) throw new ApiProblem(HttpStatus.FORBIDDEN, "请先验证邮箱，再返回登录。");
        rotateSession(session, servletRequest); session.setAttribute(USER_ID, user.getId()); session.setAttribute(AUTH_EPOCH, user.getSessionEpoch()); refreshCsrf(servletRequest, response);
        return view(user);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpSession session, HttpServletRequest request, HttpServletResponse response) { session.invalidate(); refreshCsrf(request, response); }

    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void csrf(org.springframework.security.web.csrf.CsrfToken token) { token.getToken(); }

    @GetMapping("/me")
    public Map<String, Object> me(HttpSession session) {
        Object id = session.getAttribute(USER_ID);
        if (!(id instanceof Long)) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "请先登录");
        return view(requireUser(session, users));
    }

    public static UserAccount requireUser(HttpSession session, UserRepository users) {
        Object id = session.getAttribute(USER_ID);
        if (!(id instanceof Long)) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "登录后才能进行此操作");
        UserAccount user = users.findById((Long) id).orElseThrow(() -> new ApiProblem(HttpStatus.UNAUTHORIZED, "登录已失效"));
        if (!(session.getAttribute(AUTH_EPOCH) instanceof Long epoch) || epoch != user.getSessionEpoch()) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "登录已失效");
        return user;
    }

    private Map<String, Object> view(UserAccount user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId()); result.put("email", user.getEmail()); result.put("displayName", user.getDisplayName());
        result.put("emailVerified", user.isEmailVerified());
        AccessContext context = access.forUser(user.getId());
        if (context != null) {
            result.put("role", Map.of("code", context.roleCode(), "name", context.roleName()));
            result.put("permissions", context.permissions().stream().map(Permission::code).toList());
        }
        return result;
    }

    private void rotateSession(HttpSession session, HttpServletRequest request) {
        request.changeSessionId();
    }
    private void refreshCsrf(HttpServletRequest request, HttpServletResponse response) {
        csrfTokens.saveToken(null, request, response);
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}
}
