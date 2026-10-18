package com.aitome.access;

import com.aitome.access.AccessAdminService.*;
import com.aitome.user.AuthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** FINNS-11 管理接口（契约 §5）。权限由 {@link PermissionInterceptor} 检查；保护规则在 {@link AccessAdminService}。 */
@RestController
@RequestMapping("/api/admin")
public class AccessAdminController {
    private final AccessAdminService admin;
    private final AccessService access;

    public AccessAdminController(AccessAdminService admin, AccessService access) { this.admin = admin; this.access = access; }

    @GetMapping("/users")
    @RequirePermission(Permission.ADMIN_USERS)
    public UserPage users(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String role, @RequestParam(defaultValue = "") String status,
                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size, HttpServletRequest http) {
        return admin.users(access.current(http), q, role, status, page, size);
    }

    @PatchMapping("/users/{id}/status")
    @RequirePermission(Permission.ADMIN_USERS)
    public UserItem status(@PathVariable long id, @RequestBody StatusRequest request, HttpServletRequest http) {
        return admin.changeStatus(access.current(http), id, request.status(), request.expectedVersion(), request.note());
    }

    @GetMapping("/ownership")
    @RequirePermission(Permission.ADMIN_USERS)
    public OwnershipView ownership(HttpServletRequest http) { return admin.ownership(access.current(http)); }

    @PostMapping("/ownership")
    @RequirePermission(Permission.ADMIN_USERS)
    public Map<String, Object> transfer(@RequestBody TransferRequest request, HttpServletRequest http, HttpSession session) {
        TransferOutcome outcome = admin.transferOwner(access.current(http), request.targetId(), request.expectedOwnerId(), request.expectedVersion(), request.note(), request.confirmEmail());
        // 原站长当前会话保留：换新的会话 ID 并采用新的撤销版本，此后立即按管理员权限处理；双方其他旧会话随 epoch 抬高失效（FINNS-16 UA-R14）
        http.changeSessionId();
        session.setAttribute(AuthController.AUTH_EPOCH, outcome.operatorEpoch());
        return Map.of("owner", outcome.owner(), "formerOwnerRole", outcome.formerOwnerRole());
    }

    @PatchMapping("/users/{id}/role")
    @RequirePermission(Permission.ADMIN_USERS)
    public UserItem assign(@PathVariable long id, @RequestBody AssignRequest request, HttpServletRequest http) {
        return admin.assignRole(access.current(http), id, request.roleCode(), request.expectedRoleCode(), request.note());
    }

    @GetMapping("/roles")
    @RequirePermission(Permission.ADMIN_USERS)
    public RolesView roles(HttpServletRequest http) { return admin.roles(access.current(http)); }

    @PutMapping("/roles")
    @RequirePermission(Permission.ADMIN_ROLES)
    public RolesView permissions(@RequestBody PermissionsRequest request, HttpServletRequest http) {
        return admin.updatePermissions(access.current(http), request.version(), request.permissions(), request.note());
    }

    @PostMapping("/roles/definitions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.ADMIN_ROLES)
    public RolesView create(@RequestBody RoleRequest request, HttpServletRequest http) {
        return admin.createRole(access.current(http), request.version(), request.name(), request.description(), request.copyFrom(), request.note());
    }

    @PatchMapping("/roles/definitions/{code}")
    @RequirePermission(Permission.ADMIN_ROLES)
    public RolesView update(@PathVariable String code, @RequestBody RoleRequest request, HttpServletRequest http) {
        return admin.updateRole(access.current(http), code, request.version(), request.name(), request.description(), request.note());
    }

    @DeleteMapping("/roles/definitions/{code}")
    @RequirePermission(Permission.ADMIN_ROLES)
    public RolesView delete(@PathVariable String code, @RequestBody DeleteRequest request, HttpServletRequest http) {
        return admin.deleteRole(access.current(http), code, request.version(), request.moveTo(), request.note());
    }

    @GetMapping("/audit")
    @RequirePermission(Permission.ADMIN_AUDIT)
    public AuditPage audit(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String type, @RequestParam(defaultValue = "") String role,
                           @RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return admin.audit(q, type, role, from, to, page, size);
    }

    public record AssignRequest(String roleCode, String expectedRoleCode, String note) {}
    public record StatusRequest(String status, Long expectedVersion, String note) {}
    public record TransferRequest(Long targetId, Long expectedOwnerId, Long expectedVersion, String note, String confirmEmail) {}
    public record PermissionsRequest(long version, Map<String, List<String>> permissions, String note) {}
    public record RoleRequest(long version, String name, String description, String copyFrom, String note) {}
    public record DeleteRequest(long version, String moveTo, String note) {}
}
