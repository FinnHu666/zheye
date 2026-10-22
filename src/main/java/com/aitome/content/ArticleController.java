package com.aitome.content;

import com.aitome.access.Permission;
import com.aitome.access.RequirePermission;
import com.aitome.user.AuthController;
import com.aitome.user.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * FINNS-18 B2：本人文章草稿接口（契约修订2 §4）。权限分工（PUBLISH-D09）：读取用 profile.manage，创建/保存用 post.create；
 * 归属取自服务端会话，他人草稿 404；写请求受现有 CSRF 保护。发布/取消送审/撤回/删除见 B3（契约修订2 §3.3–§3.9）。
 */
@RestController
@RequestMapping("/api/me/articles")
public class ArticleController {
    private final ArticleDraftService service;
    private final UserRepository users;

    public ArticleController(ArticleDraftService service, UserRepository users) { this.service = service; this.users = users; }

    @GetMapping @RequirePermission(Permission.PROFILE_MANAGE)
    public Map<String, Object> list(@RequestParam(defaultValue = "ALL") String state, @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpSession session) {
        return service.list(AuthController.requireUser(session, users).getId(), state, q, page, size);
    }

    @PostMapping @RequirePermission(Permission.POST_CREATE)
    public ResponseEntity<Map<String, Object>> create(@RequestBody DraftRequest request, HttpSession session) {
        Map<String, Object> view = service.create(AuthController.requireUser(session, users).getId(), request.title(), request.summary(), request.body());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/{id}") @RequirePermission(Permission.PROFILE_MANAGE)
    public Map<String, Object> read(@PathVariable long id, HttpSession session) {
        return service.get(AuthController.requireUser(session, users).getId(), id);
    }

    @PutMapping("/{id}") @RequirePermission(Permission.POST_CREATE)
    public Map<String, Object> save(@PathVariable long id, @RequestBody SaveRequest request, HttpSession session) {
        return service.save(AuthController.requireUser(session, users).getId(), id, request.title(), request.summary(), request.body(), request.expectedVersion());
    }

    @PostMapping("/{id}/publish") @RequirePermission(Permission.POST_CREATE)
    public Map<String, Object> publish(@PathVariable long id, @RequestBody PublishRequest request, HttpSession session) {
        var user = AuthController.requireUser(session, users);
        return service.publish(user.getId(), user.getDisplayName(), id, request.title(), request.summary(), request.body(), request.expectedVersion(), request.requestId());
    }

    @PostMapping("/{id}/cancel") @RequirePermission(Permission.PROFILE_MANAGE)
    public Map<String, Object> cancel(@PathVariable long id, HttpSession session) {
        var user = AuthController.requireUser(session, users);
        return service.cancel(user.getId(), user.getDisplayName(), id);
    }

    @PostMapping("/{id}/withdraw") @RequirePermission(Permission.PROFILE_MANAGE)
    public Map<String, Object> withdraw(@PathVariable long id, @RequestBody(required = false) WithdrawRequest request, HttpSession session) {
        var user = AuthController.requireUser(session, users);
        return service.withdraw(user.getId(), user.getDisplayName(), id, request == null ? null : request.confirm());
    }

    @DeleteMapping("/{id}") @RequirePermission(Permission.PROFILE_MANAGE)
    public Map<String, Object> delete(@PathVariable long id, HttpSession session) {
        var user = AuthController.requireUser(session, users);
        return service.delete(user.getId(), user.getDisplayName(), id);
    }

    public record DraftRequest(String title, String summary, String body) {}
    public record PublishRequest(String title, String summary, String body, Long expectedVersion, String requestId) {}
    public record WithdrawRequest(Boolean confirm) {}
    public record SaveRequest(String title, String summary, String body, Long expectedVersion) {}
}
