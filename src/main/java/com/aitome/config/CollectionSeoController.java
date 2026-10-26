package com.aitome.config;

import com.aitome.access.AccessService;
import com.aitome.content.CollectionPublicationService;
import com.aitome.common.ApiExceptionHandler.ApiProblem;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;

@Controller
public class CollectionSeoController {
    private final CollectionPublicationService publications;private final AccessService access;
    public CollectionSeoController(CollectionPublicationService publications,AccessService access){this.publications=publications;this.access=access;}
    @GetMapping(value="/collections/{id:[0-9]+}",produces="text/html;charset=UTF-8") @ResponseBody
    public ResponseEntity<String> page(@PathVariable long id,HttpServletRequest request) throws IOException {
        String html=new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
        try {
            var outline=publications.read(id,access.current(request));
            String title=outline.metadata()!=null&&!outline.metadata().seoTitle().isBlank()?outline.metadata().seoTitle():outline.collection().name();
            html=html.replaceAll("(?i)<meta\\s+name=[\"']description[\"'][^>]*>","");
            html=html.replaceFirst("<title>[^<]*</title>",Matcher.quoteReplacement("<title>"+HtmlUtils.htmlEscape(title)+" · FINNS</title>"));
            html=html.replace("</head>","<meta name=\"description\" content=\""+HtmlUtils.htmlEscape(outline.collection().description())+"\"></head>");
        } catch (ApiProblem e) { /* 不可见内容保留站点通用标题，不向爬虫泄漏私人信息。 */ }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(html);
    }
}
