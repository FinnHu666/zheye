package com.aitome.access;

import com.aitome.access.AccessAdminService.*;
import jakarta.servlet.http.HttpServletRequest;
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
    public UserPage users(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String role,
                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return admin.users(q, role, page, size);
    }

    @PatchMapping("/users/{id}/role")
    @RequirePermission(Permission.ADMIN_USERS)
    public UserItem assign(@PathVariable long id, @RequestBody AssignRequest request, HttpServletRequest http) {
        return admin.assignRole(access.current(http), id, request.roleCode(), request.expectedRoleCode(), request.note());
    }

    @GetMapping("/roles")
    @RequirePermission(Permission.ADMIN_USERS)
    public RolesView roles() { return admin.roles(); }

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
    public record PermissionsRequest(long version, Map<String, List<String>> permissions, String note) {}
    public record RoleRequest(long version, String name, String description, String copyFrom, String note) {}
    public record DeleteRequest(long version, String moveTo, String note) {}
}
