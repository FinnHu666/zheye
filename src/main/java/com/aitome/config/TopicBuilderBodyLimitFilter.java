package com.aitome.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bound both Content-Length and chunked JSON before Jackson allocates a document. */
@Component @Order(Ordered.LOWEST_PRECEDENCE)
public class TopicBuilderBodyLimitFilter extends OncePerRequestFilter {
    private static final int MAX=4*1024*1024;
    @Override protected boolean shouldNotFilter(HttpServletRequest request){String path=request.getRequestURI();return !(request.getMethod().equals("POST")||request.getMethod().equals("PUT"))||!(path.equals("/api/me/ai-settings/deepseek")||path.startsWith("/api/me/topic-builder/")||path.matches("/api/me/collections/[0-9]+/builder-document"));}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        int limit=request.getRequestURI().equals("/api/me/ai-settings/deepseek")?4096:MAX;
        byte[] bytes=request.getContentLengthLong()>limit?null:request.getInputStream().readNBytes(limit+1);
        if(bytes==null||bytes.length>limit){response.setStatus(413);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"status\":413,\"message\":\"本次请求超过容量限制，请精简内容\"}");return;}
        chain.doFilter(new HttpServletRequestWrapper(request){
            @Override public ServletInputStream getInputStream(){ByteArrayInputStream input=new ByteArrayInputStream(bytes);return new ServletInputStream(){public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new IllegalStateException("Synchronous JSON request only");}};}
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },response);
    }
}
