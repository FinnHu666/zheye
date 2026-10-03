package com.aitome.user;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public static final String USER_ID = "USER_ID";
    private final UserRepository users;
    private final BCryptPasswordEncoder encoder;
    private final CookieCsrfTokenRepository csrfTokens;

    public AuthController(UserRepository users, BCryptPasswordEncoder encoder, CookieCsrfTokenRepository csrfTokens) {
        this.users = users; this.encoder = encoder; this.csrfTokens = csrfTokens;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest request, HttpSession session, HttpServletRequest servletRequest, HttpServletResponse response) {
        if (users.existsByEmailIgnoreCase(request.email())) throw new ApiProblem(HttpStatus.CONFLICT, "该邮箱已注册");
        UserAccount user = users.save(new UserAccount(request.email(), encoder.encode(request.password()), request.displayName()));
        rotateSession(session, servletRequest); session.setAttribute(USER_ID, user.getId()); refreshCsrf(servletRequest, response);
        return view(user);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request, HttpSession session, HttpServletRequest servletRequest, HttpServletResponse response) {
        UserAccount user = users.findByEmailIgnoreCase(request.email())
                .filter(found -> encoder.matches(request.password(), found.getPasswordHash()))
                .orElseThrow(() -> new ApiProblem(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确"));
        rotateSession(session, servletRequest); session.setAttribute(USER_ID, user.getId()); refreshCsrf(servletRequest, response);
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
        return view(users.findById((Long) id).orElseThrow(() -> new ApiProblem(HttpStatus.UNAUTHORIZED, "登录已失效")));
    }

    public static UserAccount requireUser(HttpSession session, UserRepository users) {
        Object id = session.getAttribute(USER_ID);
        if (!(id instanceof Long)) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "登录后才能进行此操作");
        return users.findById((Long) id).orElseThrow(() -> new ApiProblem(HttpStatus.UNAUTHORIZED, "登录已失效"));
    }

    private Map<String, Object> view(UserAccount user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId()); result.put("email", user.getEmail()); result.put("displayName", user.getDisplayName());
        return result;
    }

    private void rotateSession(HttpSession session, HttpServletRequest request) {
        request.changeSessionId();
    }
    private void refreshCsrf(HttpServletRequest request, HttpServletResponse response) {
        csrfTokens.saveToken(null, request, response);
    }

    public record RegisterRequest(@NotBlank @Email String email,
                                  @NotBlank @Size(min = 8, max = 72, message = "密码需要 8–72 个字符") String password,
                                  @NotBlank @Size(max = 40, message = "昵称最多 40 个字符") String displayName) {}
    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}
}
