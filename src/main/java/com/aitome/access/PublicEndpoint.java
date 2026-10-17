package com.aitome.access;

import java.lang.annotation.*;

/** 明确声明接口不需要角色权限（公开内容、登录流程、访问上报，或使用独立密钥的本地工具）。内容范围仍由接口自身过滤。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicEndpoint {
    /** 为什么不需要权限，便于审阅。 */
    String value();
}
