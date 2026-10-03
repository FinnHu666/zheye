package com.aitome.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 将 Vue Router 的 history 路由交给同一个前端入口渲染。API 路径不会进入此控制器。 */
@Controller
public class SpaController {
    @GetMapping({"/collections", "/collections/**", "/vps", "/private/tools", "/me", "/me/**", "/my/**"})
    public String frontendRoute() {
        return "forward:/index.html";
    }
}
