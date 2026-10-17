package com.aitome.access;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** 当前请求的身份与权限（每次请求从数据库读取，角色或权限变更在下一次请求即生效）。 */
public record AccessContext(Long userId, String displayName, Long roleId, String roleCode, String roleName,
                            String systemKind, Set<Permission> permissions) {
    public static AccessContext guest() {
        EnumSet<Permission> guest = EnumSet.noneOf(Permission.class);
        for (Permission p : Permission.values()) if (p.guest()) guest.add(p);
        return new AccessContext(null, null, null, null, "游客", null, Collections.unmodifiableSet(guest));
    }

    public boolean isGuest() { return userId == null; }
    public boolean has(Permission permission) { return permissions.contains(permission); }
}
