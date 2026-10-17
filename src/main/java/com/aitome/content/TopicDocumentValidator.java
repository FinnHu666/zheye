package com.aitome.content;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class TopicDocumentValidator {
    private final TopicHtmlPolicy html;
    private final ObjectMapper json;
    public static final List<String> STYLES=List.of("guide","cards","timeline","docs");
    public TopicDocumentValidator(TopicHtmlPolicy html,ObjectMapper json){this.html=html;this.json=json;}
    public void form(JsonNode form) {
        object(form,"创建要求");
        text(form,"topic",80,true);text(form,"audience",160,true);text(form,"purpose",80,true);
        text(form,"goal",300,false);text(form,"styleNote",200,false);text(form,"source",16000,false);text(form,"constraints",500,false);text(form,"context",200,false);
        choice(form,"style",STYLES);choice(form,"density",List.of("comfortable","compact"));
    }
    public void document(JsonNode doc) {
        object(doc,"页面文档");
        if(!doc.path("schemaVersion").isIntegralNumber()||doc.path("schemaVersion").asInt()!=1)throw bad("页面文档版本不支持");
        form(doc.get("form"));form(doc.get("confirmedForm"));choice(doc,"layoutMode",List.of("flow","canvas"));
        if(!doc.path("withoutSource").isBoolean())throw bad("页面需要记录资料状态");
        JsonNode blocks=doc.get("blocks");if(blocks==null||!blocks.isArray()||blocks.size()>html.limit("maxBlocks"))throw bad("页面最多 50 个模块");
        Set<String> ids=new HashSet<>();int codeCount=0;
        for(JsonNode block:blocks) {
            block(block,doc.path("layoutMode").asText().equals("canvas"));
            if(!ids.add(block.path("id").asText()))throw bad("模块标识不能重复");
            if(block.path("kind").asText().equals("html")&&++codeCount>html.limit("maxHtmlBlocks"))throw bad("最多 20 个 HTML 模块");
        }
        if(doc.has("reviewNotes")) {
            JsonNode notes=doc.get("reviewNotes");
            if(!notes.isArray()||notes.size()>20)throw bad("核验提示最多 20 条");
            for(JsonNode note:notes)if(!note.isTextual()||note.asText().length()>300)throw bad("每条核验提示最多 300 字");
        }
        try { if(json.writeValueAsBytes(doc).length>html.limit("maxDocumentBytes"))throw new ApiProblem(HttpStatus.PAYLOAD_TOO_LARGE,"页面文档最多 4 MiB"); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw bad("页面文档无法读取");}
    }
    public void block(JsonNode block,boolean requireLayout) {
        object(block,"模块");String id=text(block,"id",36,true);
        try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw bad("模块标识需要有效 UUID");}
            choice(block,"kind",List.of("text","steps","question","html"));boolean code=block.path("kind").asText().equals("html");
            text(block,"title",100,code);text(block,"body",4000,false);
            if(code) {
                JsonNode value=block.get("code");object(value,"代码模块");
                if(!value.path("rendererVersion").isIntegralNumber()||value.path("rendererVersion").asInt()!=1)throw bad("HTML 渲染版本不支持");
                html.validate(text(value,"source",html.limit("maxLength"),true));number(value,"frameHeight",120,1200,true);
            } else if(block.hasNonNull("code"))throw bad("普通模块不能包含代码");
            JsonNode layout=block.get("layout");
            if(layout!=null&&!layout.isNull()) {
                object(layout,"布局");double x=number(layout,"x",0,80,false),w=number(layout,"w",20,100,false);
                if(x+w>100.00001)throw bad("模块位置和宽度超出画布");
                number(layout,"y",0,10000,false);number(layout,"h",120,1600,false);
            } else if(requireLayout)throw bad("自由画布模块缺少位置与尺寸");
    }
    public static void object(JsonNode value,String name){if(value==null||!value.isObject())throw bad(name+"需要完整对象");}
    public static String text(JsonNode value,String field,int max,boolean required) {
        JsonNode n=value.get(field);if(n==null||!n.isTextual())throw bad(field+"需要文字");String s=n.asText();
        if(s.length()>max||(required&&s.isBlank()))throw bad(field+"需要"+(required?"非空且":"")+"最多 "+max+" 个字符");return s;
    }
    private static void choice(JsonNode value,String key,List<String> values){if(!values.contains(value.path(key).asText()))throw bad(key+"不在支持范围内");}
    private static double number(JsonNode value,String key,double min,double max,boolean integer) {
        JsonNode n=value.get(key);if(n==null||!n.isNumber()||(integer&&!n.isIntegralNumber()))throw bad(key+"需要有效数值");double v=n.asDouble();
        if(!Double.isFinite(v)||v<min||v>max)throw bad(key+"超出允许范围");return v;
    }
    public static ApiProblem bad(String message){return new ApiProblem(HttpStatus.BAD_REQUEST,message);}
}
