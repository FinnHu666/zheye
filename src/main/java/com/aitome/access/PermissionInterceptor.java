package com.aitome.access;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.aitome.user.AuthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** 按 {@link RequirePermission} 逐请求检查当前角色（契约 §4）。 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {
    private final AccessService access;

    public PermissionInterceptor(AccessService access) { this.access = access; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        RequirePermission required = required(method);
        if (required == null) return true;
        AccessContext context = access.current(request);
        if (context == null) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "登录已失效");
        if (context.isGuest()) {
            var session = request.getSession(false);
            boolean stale = session != null && session.getAttribute(AuthController.USER_ID) instanceof Long;
            throw new ApiProblem(HttpStatus.UNAUTHORIZED, stale ? "登录已失效" : "请先登录");
        }
        for (Permission permission : required.value())
            if (!context.has(permission)) throw new ApiProblem(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
        return true;
    }

    /** 方法上的声明优先于类上的声明；方法标注 {@link PublicEndpoint} 时不要求权限。 */
    static RequirePermission required(HandlerMethod method) {
        if (AnnotatedElementUtils.hasAnnotation(method.getMethod(), PublicEndpoint.class)) return null;
        RequirePermission onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequirePermission.class);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequirePermission.class);
    }
}
