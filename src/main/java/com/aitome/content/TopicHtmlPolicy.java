package com.aitome.content;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

@Component
public class TopicHtmlPolicy {
    private final JsonNode policy;
    private final Set<String> tags, attributes;
    private static final Pattern RESOURCE=Pattern.compile("@import\\b|url\\s*\\(",Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_ESCAPE=Pattern.compile("\\\\([0-9a-fA-F]{1,6})\\s?|\\\\([^\\r\\n])");
    private boolean hasResource(String css) {
        String normalized=CSS_ESCAPE.matcher(css.replaceAll("(?s)/\\*.*?\\*/","")).replaceAll(match -> Matcher.quoteReplacement(match.group(1)==null?match.group(2):new String(Character.toChars(Math.min(Integer.parseInt(match.group(1),16),0x10ffff)))));
        return RESOURCE.matcher(normalized).find();
    }
    public TopicHtmlPolicy(ObjectMapper json) throws IOException {
        try(var stream=new ClassPathResource("topic-builder/html-policy.json").getInputStream()) { policy=json.readTree(stream); }
        tags=Set.of(policy.get("tags").asText().split(" ")); attributes=Set.of(policy.get("attributes").asText().split(" "));
    }
    public JsonNode descriptor(){return policy.deepCopy();}
    public int limit(String name){return policy.get(name).asInt();}
    public void validate(String source) {
        if(source==null||source.isBlank()||source.length()>limit("maxLength"))throw bad("HTML 代码需要非空，最多 60,000 字符");
        Element body=Jsoup.parseBodyFragment(source).body();
        int[] count={0};boolean[] visible={false};inspect(body,0,count,visible);
        if(!visible[0])throw bad("HTML 中没有可显示的内容");
    }
    private void inspect(Node parent,int depth,int[] count,boolean[] visible) {
        if(depth>limit("maxDepth"))throw bad("HTML 嵌套超过 40 层");
        for(Node node:parent.childNodes()) {
            if(++count[0]>limit("maxNodes"))throw bad("HTML 节点超过 2,000 个");
            if(node instanceof org.jsoup.nodes.Comment)continue;
            if(node instanceof TextNode text){if(!text.getWholeText().isBlank())visible[0]=true;continue;}
            if(!(node instanceof Element element))continue;
            String tag=element.normalName();
            if(tag.equals("title"))continue;
            if(tag.equals("meta")&&!element.hasAttr("http-equiv")&&(element.hasAttr("charset")||element.attr("name").equalsIgnoreCase("viewport")))continue;
            if(tag.equals("style")) {
                if(!element.attributes().isEmpty()||hasResource(element.data()))throw bad("style 不支持属性或外部资源");
                continue;
            }
            if(!tags.contains(tag))throw bad("不支持 HTML 标签 <"+tag+">");
            for(Attribute attribute:element.attributes()) {
                String name=attribute.getKey().toLowerCase(Locale.ROOT),value=attribute.getValue();
                if(tag.equals("a")&&name.equals("href")&&value.matches("#[a-zA-Z0-9_-]+"))continue;
                if(tag.equals("img")&&name.equals("src")&&value.matches("(?i)data:image/(png|jpeg|gif|webp|avif);base64,[a-z0-9+/=\\s]+"))continue;
                if(!attributes.contains(name)||name.startsWith("on"))throw bad("不支持 HTML 属性 "+name);
                if(name.equals("style")&&hasResource(value))throw bad("行内样式不支持外部资源");
            }
            if(tag.equals("img")&&!element.hasAttr("src"))throw bad("图片需要内嵌数据");
            if(tag.equals("img")||tag.equals("hr"))visible[0]=true;
            inspect(element,depth+1,count,visible);
        }
    }
    private ApiProblem bad(String message){return new ApiProblem(HttpStatus.BAD_REQUEST,message);}
}
