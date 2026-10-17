package com.aitome.stats;

import com.aitome.access.PublicEndpoint;
import com.aitome.common.ApiExceptionHandler.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * FINNS-10：进入网站时上报一次。去重依赖 30 分钟滑动过期的 HttpOnly Cookie，服务端不保存访客明细。
 * 该接口不读写身份数据，因此不要求 CSRF 令牌，改为校验 JSON 内容类型与同源 Origin。
 */
@RestController
@PublicEndpoint("访问上报，不涉及身份数据")
public class SiteVisitController {
    public static final String COOKIE = "FINNS_VISIT";
    public static final int WINDOW_SECONDS = 30 * 60;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SiteVisitController.class);
    private final SiteVisitService visits;

    public SiteVisitController(SiteVisitService visits) { this.visits = visits; }

    @PostMapping("/api/site-visits")
    public VisitResult enter(HttpServletRequest request, HttpServletResponse response) {
        requireSameOriginJson(request);
        String existing = visitCookie(request);
        if (existing != null) {
            writeCookie(request, response, existing);
            return new VisitResult(false, visits.currentTotal());
        }
        long total;
        try { total = visits.recordEntry(); }
        catch (RuntimeException failure) {
            log.warn("访问计数失败：{}", failure.toString());
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "访问计数暂不可用");
        }
        byte[] token = new byte[16];
        RANDOM.nextBytes(token);
        writeCookie(request, response, Base64.getUrlEncoder().withoutPadding().encodeToString(token));
        return new VisitResult(true, total);
    }

    static String visitCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies())
            if (COOKIE.equals(cookie.getName()) && cookie.getValue() != null && cookie.getValue().matches("[A-Za-z0-9_-]{8,64}")) return cookie.getValue();
        return null;
    }

    static void writeCookie(HttpServletRequest request, HttpServletResponse response, String value) {
        response.addHeader("Set-Cookie", COOKIE + "=" + value + "; Max-Age=" + WINDOW_SECONDS + "; Path=/; HttpOnly; SameSite=Lax" + (request.isSecure() ? "; Secure" : ""));
    }

    private static void requireSameOriginJson(HttpServletRequest request) {
        String type = request.getContentType();
        if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(type)))
            throw new ApiProblem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求格式不正确");
        String origin = request.getHeader("Origin");
        if (origin == null) return;
        try {
            // 只比较主机名：反向代理后端口/协议可能与浏览器看到的不同。
            String host = URI.create(origin).getHost();
            if (host == null || !request.getServerName().equalsIgnoreCase(host))
                throw new ApiProblem(HttpStatus.FORBIDDEN, "请求来源不被允许");
        } catch (IllegalArgumentException malformed) {
            throw new ApiProblem(HttpStatus.FORBIDDEN, "请求来源不被允许");
        }
    }

    public record VisitResult(boolean counted, long total) {}
}
