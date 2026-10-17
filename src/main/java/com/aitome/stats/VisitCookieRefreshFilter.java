package com.aitome.stats;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 浏览中的接口请求为访问 Cookie 续期，使“30 分钟无活动”按最后一次活动计算，且不需要额外心跳请求。 */
@Component
public class VisitCookieRefreshFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.equals("/api/site-visits");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String visit = SiteVisitController.visitCookie(request);
        if (visit != null) SiteVisitController.writeCookie(request, response, visit);
        chain.doFilter(request, response);
    }
}
