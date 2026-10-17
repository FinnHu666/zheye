package com.aitome.access;

import java.lang.annotation.*;

/**
 * 接口所需权限（全部满足才放行）。未登录 401，缺少权限 403。
 * 无需权限的公开接口用 {@link PublicEndpoint} 显式标注，便于覆盖性测试发现遗漏。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {
    Permission[] value();
}
