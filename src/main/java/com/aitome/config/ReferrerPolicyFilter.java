package com.aitome.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * 全站不发送来源（PUBLISH-D14 A1、D15 U1）：文章里的外链图片与外链不会把读者正在看的页面地址告诉外站。
 * 在进入后续处理前设置，转发到 index.html 的页面与 /api/** 响应都带上。
 */
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class ReferrerPolicyFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("Referrer-Policy", "no-referrer");
        chain.doFilter(request, response);
    }
}
