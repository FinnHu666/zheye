package com.aitome;

import com.aitome.content.*;
import com.aitome.user.AiCredentialService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"finns.ai.api-key=shared-must-never-be-used","finns.ai.credential-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","finns.ai.timeout-seconds=1"}) @AutoConfigureMockMvc
class TopicBuilderTests {
    static HttpServer upstream;
    static final AtomicReference<String> mode=new AtomicReference<>("valid");
    static final AtomicReference<JsonNode> received=new AtomicReference<>();
    static final ObjectMapper mapper=new ObjectMapper();
    static {
        try {
            upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            upstream.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));
            upstream.createContext("/chat/completions",exchange->{
                received.set(mapper.readTree(exchange.getRequestBody()));String m=mode.get();int status=m.equals("rate")?429:m.equals("auth")?401:200;
                try {
                    if(m.equals("timeout"))Thread.sleep(1500);
                    String value=switch(m){case "chat"->"{\"reply\":\"请确认问卷\",\"form\":{\"topic\":\"AI改名\",\"audience\":\"初学者\"}}";case "bad"->"{broken";case "missing"->"{\"blocks\":[{\"title\":\"无正文\",\"kind\":\"text\"}],\"warnings\":[]}";case "oversized"->"{\"blocks\":[{\"title\":\"过长\",\"kind\":\"text\",\"origin\":\"source\",\"body\":\""+"字".repeat(4001)+"\"}],\"warnings\":[]}";case "empty"->"";default->"{\"blocks\":[{\"title\":\"准备\",\"body\":\"记录预算与租期\",\"kind\":\"steps\",\"origin\":\"source\"},{\"title\":\"提示\",\"body\":\"补充自己的检查项\",\"kind\":\"text\",\"origin\":\"suggestion\"}],\"warnings\":[\"请核验\"]}";};
                    byte[] bytes=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason",m.equals("truncated")?"length":"stop","message",Map.of("content",value))),"usage",Map.of("prompt_tokens",50,"completion_tokens",70,"total_tokens",120)));
                    exchange.sendResponseHeaders(status,bytes.length);
                    if(m.equals("slowbody"))Thread.sleep(1500);
                    exchange.getResponseBody().write(bytes);
                } catch(InterruptedException e){Thread.currentThread().interrupt();} finally{exchange.close();}
            });upstream.start();
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("finns.ai.base-url",()->"http://127.0.0.1:"+upstream.getAddress().getPort());}
    @AfterAll static void stop(){upstream.stop(0);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired TopicHtmlPolicy html;@Autowired TopicDocumentValidator validator;@Autowired AiCredentialService credentials;@Autowired JdbcTemplate jdbc;
    MockHttpSession user() throws Exception {MockHttpSession session=new MockHttpSession();TestUsers.registerAndLogin(mvc,jdbc,session,UUID.randomUUID()+"@finns.dev","password123","专题验收用户").andExpect(status().isOk());write(put("/api/me/ai-settings/deepseek"),session,Map.of("key","test-only"),200);return session;}
    JsonNode write(MockHttpServletRequestBuilder builder,MockHttpSession session,Object body,int expected) throws Exception {
        Cookie token=mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        var request=builder.session(session).cookie(token).header("X-XSRF-TOKEN",token.getValue()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        String response=mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return response.isBlank()?json.createObjectNode():json.readTree(response);
    }
    ObjectNode form(){return json.valueToTree(Map.of("topic","短租指南","audience","大学生","purpose","避坑","goal","整理准备事项","style","guide","density","comfortable","styleNote","","source","","constraints","","context","案例资料"));}
    ObjectNode document(){ObjectNode doc=json.createObjectNode();doc.put("schemaVersion",1);doc.set("form",form());doc.set("confirmedForm",form());doc.put("layoutMode","canvas");doc.put("withoutSource",true);ObjectNode b=doc.putArray("blocks").addObject();b.put("id",UUID.randomUUID().toString());b.put("kind","text");b.put("title","准备");b.put("body","待补充");b.set("layout",json.valueToTree(Map.of("x",3,"y",20,"w",45,"h",240)));return doc;}

    @Test void savesIdempotentlyConflictsAndProtectsLegacyBoundaries() throws Exception {
        var a=user();var b=user();ObjectNode doc=document();var payload=Map.of("idempotencyKey",UUID.randomUUID().toString(),"document",doc);
        JsonNode created=write(post("/api/me/topic-builder/drafts"),a,payload,201),retry=write(post("/api/me/topic-builder/drafts"),a,payload,201);long id=created.path("id").asLong();
        assertEquals(created.path("id"),retry.path("id"));assertEquals(0,created.path("revision").asLong());
        mvc.perform(get("/api/me/collections/"+id+"/builder-document").session(b)).andExpect(status().isForbidden());
        mvc.perform(get("/api/me/collections/"+id+"/builder-document")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/collections/"+id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/me/collections/"+id+"/draft").session(a)).andExpect(status().isConflict());
        write(post("/api/me/collections/"+id+"/publish"),a,Map.of("revision",0),409);
        write(post("/api/me/collections/"+id+"/duplicate"),a,Map.of("structureOnly",true),409);
        ((ObjectNode)doc.get("form")).put("topic","保存后的标题");
        write(post("/api/me/topic-builder/drafts"),a,payload,409);
        JsonNode saved=write(put("/api/me/collections/"+id+"/builder-document"),a,Map.of("revision",0,"document",doc),200);assertEquals(1,saved.path("revision").asLong());
        write(put("/api/me/collections/"+id+"/builder-document"),a,Map.of("revision",0,"document",doc),409);
        write(put("/api/me/collections/"+id+"/builder-document"),b,Map.of("revision",1,"document",doc),403);
        mvc.perform(get("/api/me/collections/"+id+"/builder-document").session(a)).andExpect(jsonPath("$.document.form.topic").value("保存后的标题")).andExpect(jsonPath("$.document.blocks[0].layout.x").value(3));
        mvc.perform(get("/api/me/collections").session(a)).andExpect(jsonPath("$.items[0].editorType").value("BUILDER")).andExpect(jsonPath("$.items[0].itemCount").value(1));
        JsonNode separate=write(post("/api/me/topic-builder/drafts"),b,Map.of("idempotencyKey",payload.get("idempotencyKey"),"document",doc),201);assertNotEquals(id,separate.path("id").asLong());
        write(post("/api/me/topic-builder/drafts"),new MockHttpSession(),Map.of("idempotencyKey",UUID.randomUUID().toString(),"document",doc),401);
        mvc.perform(put("/api/me/collections/"+id+"/builder-document").session(a).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/me/collections/"+id+"/builder/preview")).andExpect(forwardedUrl("/index.html"));
    }
    @Test void generatesSnapshotAndChatPreservesAnswers() throws Exception {
        var session=user();ObjectNode f=form();f.put("source","我的案例：先记录预算与租期。");mode.set("valid");
        JsonNode result=write(post("/api/me/topic-builder/generate"),session,Map.of("form",f),200);
        assertEquals(f,result.path("document").path("confirmedForm"));assertTrue(result.path("document").path("blocks").get(1).path("body").asText().startsWith("【AI 建议"));
        assertFalse(received.get().path("stream").asBoolean());assertEquals("disabled",received.get().path("thinking").path("type").asText());assertEquals(8192,received.get().path("max_tokens").asInt());
        assertEquals("json_object",received.get().path("response_format").path("type").asText());
        mode.set("chat");f.put("audience","");result=write(post("/api/me/topic-builder/chat"),session,Map.of("form",f,"messages",List.of(Map.of("role","user","text","给新手看"))),200);
        assertEquals("短租指南",result.path("form").path("topic").asText());assertEquals("初学者",result.path("form").path("audience").asText());assertEquals(2048,received.get().path("max_tokens").asInt());
        f.put("source","");f.put("audience","大学生");mode.set("rate");assertEquals("structure-only",write(post("/api/me/topic-builder/generate"),session,Map.of("form",f),200).path("model").asText());
    }
    @Test void modelFailuresAreExplicitAndNeverCreateDrafts() throws Exception {
        var session=user();ObjectNode f=form();f.put("source","固定非敏感资料");
        for(String m:List.of("rate","auth","bad","empty","missing","oversized","truncated","timeout","slowbody")) {mode.set(m);JsonNode result=write(post("/api/me/topic-builder/generate"),session,Map.of("form",f),List.of("timeout","slowbody").contains(m)?504:502);assertTrue(result.has("message"));assertFalse(result.toString().contains("test-only"));}
        mvc.perform(get("/api/me/collections").session(session)).andExpect(jsonPath("$.items.length()").value(0));
        DeepSeekClient disabled=new DeepSeekClient(json,new AiCredentialService(jdbc,""),"https://api.deepseek.com","deepseek-flash",60);assertThrows(ApiProblem.class,()->disabled.call(1,"JSON",List.of(),2048));
    }
    @Test void oneRequestPerUserAndUnknownChatRolesAreRejected() throws Exception {
        DeepSeekClient client=new DeepSeekClient(json,credentials,"http://127.0.0.1:"+upstream.getAddress().getPort(),"fixture",1);
        var configured=user();long uid=((Number)configured.getAttribute("USER_ID")).longValue();
        mode.set("timeout");received.set(null);
        CompletableFuture<Void> first=CompletableFuture.runAsync(()->assertThrows(ApiProblem.class,()->client.call(uid,"JSON",List.of(),2048)));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(received.get()==null&&System.nanoTime()<deadline)Thread.sleep(5);
        ApiProblem problem=assertThrows(ApiProblem.class,()->client.call(uid,"JSON",List.of(),2048));assertTrue(problem.getMessage().contains("已有一个"));first.get(3,TimeUnit.SECONDS);
        mode.set("valid");assertDoesNotThrow(()->client.call(uid,"JSON",List.of(),2048));
        var session=user();write(post("/api/me/topic-builder/chat"),session,Map.of("form",form(),"messages",List.of(Map.of("text","内容"))),400);
    }
    @Test void htmlFixturesCapacityAndUnknownVersions() throws Exception {
        try(var stream=getClass().getResourceAsStream("/topic-builder/html-fixtures.json")){for(JsonNode f:json.readTree(stream)){if(f.path("valid").asBoolean())assertDoesNotThrow(()->html.validate(f.path("source").asText()),f.path("name").asText());else assertThrows(ApiProblem.class,()->html.validate(f.path("source").asText()),f.path("name").asText());}}
        assertDoesNotThrow(()->html.validate("<p>"+"x".repeat(59993)+"</p>"));assertThrows(ApiProblem.class,()->html.validate("<p>"+"x".repeat(59994)+"</p>"));
        assertThrows(ApiProblem.class,()->html.validate("<div>".repeat(42)+"x"+"</div>".repeat(42)));
        assertThrows(ApiProblem.class,()->html.validate("<br>".repeat(2001)));
        var session=user();ObjectNode doc=document();ObjectNode block=(ObjectNode)doc.path("blocks").get(0);block.put("kind","html");block.set("code",json.valueToTree(Map.of("source","<p>安全模块</p>","rendererVersion",2,"frameHeight",360)));
        write(post("/api/me/topic-builder/drafts"),session,Map.of("idempotencyKey",UUID.randomUUID().toString(),"document",doc),400);
        ((ObjectNode)block.path("code")).put("rendererVersion",1);write(post("/api/me/topic-builder/drafts"),session,Map.of("idempotencyKey",UUID.randomUUID().toString(),"document",doc),201);
        doc.put("schemaVersion",2);assertThrows(ApiProblem.class,()->validator.document(doc));doc.put("schemaVersion",1);
        ArrayNode blocks=(ArrayNode)doc.path("blocks");for(int i=1;i<21;i++){ObjectNode extra=block.deepCopy();extra.put("id",UUID.randomUUID().toString());blocks.add(extra);}assertThrows(ApiProblem.class,()->validator.document(doc));
        blocks.remove(20);assertDoesNotThrow(()->validator.document(doc));
        for(JsonNode item:blocks){((ObjectNode)item).put("kind","text");((ObjectNode)item).remove("code");}
        for(int i=20;i<50;i++){ObjectNode extra=block.deepCopy();extra.put("id",UUID.randomUUID().toString());blocks.add(extra);}assertDoesNotThrow(()->validator.document(doc));
        ObjectNode extra=block.deepCopy();extra.put("id",UUID.randomUUID().toString());blocks.add(extra);assertThrows(ApiProblem.class,()->validator.document(doc));
        write(post("/api/me/topic-builder/drafts"),session,Map.of("padding","x".repeat(4194304)),413);
    }
    @Test void personalKeysAreEncryptedIsolatedReplaceableAndNeverUseSharedKey() throws Exception {
        var a=user();var b=user();long aid=((Number)a.getAttribute("USER_ID")).longValue(),bid=((Number)b.getAttribute("USER_ID")).longValue();
        String secret="test-personal-alpha-1234";
        JsonNode status=write(put("/api/me/ai-settings/deepseek"),a,Map.of("key",secret),200);
        assertEquals("1234",status.path("lastFour").asText());assertFalse(status.toString().contains(secret));
        String encrypted=jdbc.queryForObject("select ciphertext from user_ai_credentials where user_id=?",String.class,aid);
        assertFalse(encrypted.contains(secret));assertEquals(secret,credentials.key(aid));assertEquals("test-only",credentials.key(bid));
        AiCredentialService restarted=new AiCredentialService(jdbc,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");assertEquals(secret,restarted.key(aid));
        assertThrows(ApiProblem.class,()->new AiCredentialService(jdbc,"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=").key(aid));
        assertThrows(ApiProblem.class,()->new AiCredentialService(jdbc,"").save(aid,secret));
        write(put("/api/me/ai-settings/deepseek"),a,Map.of("key","replacement-5678"),200);assertEquals("replacement-5678",credentials.key(aid));
        write(put("/api/me/ai-settings/deepseek"),a,Map.of("key","bad\nkey"),400);assertEquals("replacement-5678",credentials.key(aid));
        write(delete("/api/me/ai-settings/deepseek"),a,Map.of(),200);
        mvc.perform(get("/api/me/topic-builder/capabilities").session(a)).andExpect(jsonPath("$.aiAvailable").value(false));
        ObjectNode f=form();f.put("source","固定资料");received.set(null);
        write(post("/api/me/topic-builder/generate"),a,Map.of("form",f),503);assertNull(received.get());
        write(post("/api/me/topic-builder/chat"),a,Map.of("form",f,"messages",List.of(Map.of("role","user","text","帮我整理"))),503);assertNull(received.get());
        f.put("source","");write(post("/api/me/topic-builder/generate"),a,Map.of("form",f),200);assertNull(received.get());
        mvc.perform(get("/api/me/ai-settings/deepseek")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/me/ai-settings/deepseek").session(a).contentType(MediaType.APPLICATION_JSON).content("{} ")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/me/ai-settings/deepseek").session(b)).andExpect(status().isForbidden());
        mvc.perform(get("/me/ai-settings")).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/index.html")).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void favoritesAreIndependentSnapshotsOfEveryKindAndPrivate() throws Exception {
        var a=user();var b=user();ObjectNode doc=document();ObjectNode block=(ObjectNode)doc.path("blocks").get(0);
        for(String kind:List.of("text","steps","question","html")) {
            block.put("kind",kind);block.put("body","原始快照");
            if(kind.equals("html"))block.set("code",json.valueToTree(Map.of("rendererVersion",1,"source","<p>时间轴</p>","frameHeight",360)));
            JsonNode created=write(post("/api/me/topic-builder/module-favorites"),a,Map.of("block",block),200);String id=created.path("id").asText();
            block.put("body","后续更改");
            mvc.perform(get("/api/me/topic-builder/module-favorites?size=1").session(a)).andExpect(jsonPath("$.items[0].block.body").value("原始快照"));
            mvc.perform(get("/api/me/topic-builder/module-favorites").session(b)).andExpect(jsonPath("$.items.length()").value(0));
            write(delete("/api/me/topic-builder/module-favorites/"+id),b,Map.of(),404);
        }
        mvc.perform(get("/api/me/topic-builder/module-favorites?size=2").session(a)).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get("/api/me/topic-builder/module-favorites?page=1&size=2").session(a)).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.hasNext").value(false));
        JsonNode list=json.readTree(mvc.perform(get("/api/me/topic-builder/module-favorites").session(a)).andReturn().getResponse().getContentAsString());
        write(delete("/api/me/topic-builder/module-favorites/"+list.path("items").get(0).path("id").asText()),a,Map.of(),200);
        ((ObjectNode)block.path("code")).put("source","<script>alert(1)</script>");write(post("/api/me/topic-builder/module-favorites"),a,Map.of("block",block),400);
        mvc.perform(get("/api/me/topic-builder/module-favorites")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/me/topic-builder/module-favorites").session(a).contentType(MediaType.APPLICATION_JSON).content("{} ")).andExpect(status().isForbidden());
    }
    @Test void editedReviewNotesPersistWithoutChangingOriginalAndOldDraftsRemainReadable() throws Exception {
        var a=user();ObjectNode doc=document();doc.putObject("generation").putArray("warnings").add("模型原始提示");
        JsonNode created=write(post("/api/me/topic-builder/drafts"),a,Map.of("idempotencyKey",UUID.randomUUID().toString(),"document",doc),201);long id=created.path("id").asLong();
        assertFalse(created.path("document").has("reviewNotes"));
        doc.putArray("reviewNotes").add("作者编辑内容");
        write(put("/api/me/collections/"+id+"/builder-document"),a,Map.of("revision",0,"document",doc),200);
        mvc.perform(get("/api/me/collections/"+id+"/builder-document").session(a)).andExpect(jsonPath("$.document.reviewNotes[0]").value("作者编辑内容")).andExpect(jsonPath("$.document.generation.warnings[0]").value("模型原始提示"));
        doc.putArray("reviewNotes");write(put("/api/me/collections/"+id+"/builder-document"),a,Map.of("revision",1,"document",doc),200);
        doc.putArray("reviewNotes").add("x".repeat(301));write(put("/api/me/collections/"+id+"/builder-document"),a,Map.of("revision",2,"document",doc),400);
        mvc.perform(get("/api/me/collections?editorType=BUILDER&size=1").session(a)).andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(get("/api/me/collections?editorType=OUTLINE").session(a)).andExpect(jsonPath("$.items.length()").value(0));
    }

}
