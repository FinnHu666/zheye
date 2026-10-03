package com.aitome;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class CollectionImportTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    MockHttpSession user() throws Exception {
        MockHttpSession session = new MockHttpSession();
        write(post("/api/auth/register"),session,Map.of("email", UUID.randomUUID()+"@finns.dev","password","password123","displayName","导入测试用户"),201);
        return session;
    }
    JsonNode write(MockHttpServletRequestBuilder builder,MockHttpSession user,Object body,int status) throws Exception {
        Cookie token=mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        var request=builder.session(user).cookie(token).header("X-XSRF-TOKEN",token.getValue());
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String response=mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return response.isBlank()?json.createObjectNode():json.readTree(response);
    }
    JsonNode parse(MockHttpSession session,String text) throws Exception { return write(post("/api/me/collection-imports/preview"),session,Map.of("text",text,"preserveWhole",false),200); }
    ObjectNode draft(MockHttpSession session) throws Exception {
        JsonNode parsed=parse(session,"# 租房\n预算说明\n## 看房\n检查采光和噪音");
        return (ObjectNode)write(post("/api/me/collection-imports/commit"),session,Map.of("idempotencyKey",UUID.randomUUID().toString(),"document",parsed.get("document")),201);
    }
    ObjectNode current(MockHttpSession session,long id)throws Exception {return (ObjectNode)json.readTree(mvc.perform(get("/api/me/collections/"+id+"/draft").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
    void publish(MockHttpSession session,JsonNode doc)throws Exception {write(post("/api/me/collections/"+doc.get("id").asLong()+"/publish"),session,Map.of("revision",doc.get("revision").asLong()),200);}

    @Test void parsesFourScenariosAndPreservesLongTextWithoutTruncation() throws Exception {
        MockHttpSession session=user();
        for(String name:List.of("租房","注册教程","锻炼计划","经验总结")) {
            JsonNode result=parse(session,"# "+name+"\n我的资料\n## 步骤\n1. 做好准备\n继续记录");
            assertTrue(result.get("canSave").asBoolean()); assertEquals(name,result.get("document").get("name").asText());
        }
        String source="资料".repeat(5000)+"\nhttps://example.com";
        JsonNode result=parse(session,source);
        StringBuilder reconstructed=new StringBuilder();
        result.get("document").get("sections").forEach(s->s.get("entries").forEach(e->{assertTrue(e.get("body").asText().length()<=4000);reconstructed.append(e.get("body").asText());}));
        assertEquals(source,reconstructed.toString());
        write(post("/api/me/collection-imports/preview"),session,Map.of("text","x".repeat(1048577)),400);
        write(post("/api/me/collection-imports/preview"),session,Map.of("text","乱码\uFFFD"),400);
        JsonNode oversized=parse(session,java.util.stream.IntStream.range(0,51).mapToObj(i->"## 章节"+i+"\n内容").collect(java.util.stream.Collectors.joining("\n")));
        assertFalse(oversized.get("canSave").asBoolean());
        write(post("/api/me/collection-imports/commit"),session,Map.of("idempotencyKey",UUID.randomUUID().toString(),"document",oversized.get("document")),400);
    }

    @Test void duplicateSubmissionsAndAppendAreAtomicAndPrivate() throws Exception {
        MockHttpSession session=user();JsonNode parsed=parse(session,"我的经验\n保留正文");
        String key=UUID.randomUUID().toString();Map<String,Object> body=Map.of("idempotencyKey",key,"document",parsed.get("document"));
        JsonNode first=write(post("/api/me/collection-imports/commit"),session,body,201);
        JsonNode retry=write(post("/api/me/collection-imports/commit"),session,body,201);assertEquals(first.get("id"),retry.get("id"));
        long id=first.get("id").asLong();mvc.perform(get("/api/collections/"+id)).andExpect(status().isNotFound());
        ObjectNode changed=(ObjectNode)parsed.get("document");changed.put("name","不同内容");
        write(post("/api/me/collection-imports/commit"),session,body,409);
        ObjectNode extra=(ObjectNode)parse(session,"# 补充\n新内容").get("document");extra.put("revision",first.get("revision").asLong());
        Map<String,Object> append=Map.of("idempotencyKey",UUID.randomUUID().toString(),"document",extra);
        JsonNode appended=write(post("/api/me/collections/"+id+"/imports"),session,append,200);
        write(post("/api/me/collections/"+id+"/imports"),session,append,200);
        assertEquals(2,appended.get("sections").size());assertEquals("我的经验\n保留正文",appended.get("sections").get(0).get("entries").get(0).get("body").asText());
        write(post("/api/me/collections/"+id+"/imports"),session,Map.of("idempotencyKey",UUID.randomUUID().toString(),"document",extra),409);
        assertEquals(2,current(session,id).get("sections").size());
    }

    @Test void snapshotIsolatesAllPublicReadersUntilExplicitRepublishAndArchive() throws Exception {
        MockHttpSession session=user();ObjectNode doc=draft(session);long id=doc.get("id").asLong();publish(session,doc);
        write(put("/api/me/collections/selected/"+id),session,null,200);
        ObjectNode updated=current(session,id);updated.put("name","尚未发布的改名");
        ((ObjectNode)updated.get("sections").get(0).get("entries").get(0)).put("body","尚未发布的正文");
        write(put("/api/me/collections/"+id+"/draft"),session,updated,200);
        mvc.perform(get("/api/collections/"+id)).andExpect(jsonPath("$.name").value("租房"));
        mvc.perform(get("/api/collections/"+id+"/outline")).andExpect(jsonPath("$.sections[0].entries[0].body").value("预算说明"));
        mvc.perform(get("/api/me/collections/selected").session(session)).andExpect(jsonPath("$.items[0].name").value("租房"));
        String publicList=mvc.perform(get("/api/collections").param("size","50")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertFalse(publicList.contains("尚未发布的改名"));
        mvc.perform(get("/api/me/collections/"+id+"/preview").session(session)).andExpect(jsonPath("$.collection.name").value("尚未发布的改名"));
        publish(session,current(session,id));mvc.perform(get("/api/collections/"+id)).andExpect(jsonPath("$.name").value("尚未发布的改名"));
        write(post("/api/me/collections/"+id+"/archive"),session,Map.of("revision",current(session,id).get("revision").asLong()),200);
        for(String suffix:List.of("","/outline","/posts"))mvc.perform(get("/api/collections/"+id+suffix)).andExpect(status().isNotFound());
        mvc.perform(get("/api/me/collections/selected").session(session)).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test void unfinishedDraftsSaveButPublishListsMissingFields() throws Exception {
        MockHttpSession session=user();
        JsonNode created=write(post("/api/me/collections/drafts"),session,Map.of("name","只有名称","format","CUSTOM","templateKey","BLANK"),201);
        long id=created.get("id").asLong();ObjectNode doc=current(session,id);
        doc.remove("description"); // The optional introduction can be absent, including during autosave.
        ArrayNode entries=(ArrayNode)doc.get("sections").get(0).get("entries");entries.add(json.valueToTree(Map.of("kind","NOTE","title","","body","")));entries.add(json.valueToTree(Map.of("kind","LINK","title","","externalUrl","")));entries.add(json.valueToTree(Map.of("kind","POST")));
        JsonNode saved=write(put("/api/me/collections/"+id+"/draft"),session,doc,200);
        JsonNode problems=write(post("/api/me/collections/"+id+"/publish"),session,Map.of("revision",saved.get("revision").asLong()),400);
        assertTrue(problems.get("message").asText().contains("第 1 条"));assertTrue(problems.get("message").asText().contains("第 2 条"));
        mvc.perform(get("/api/me/collections/"+id+"/preview").session(session)).andExpect(status().isOk());
    }

    @Test void createWithInitialBodyIsAtomicAndPublishesWithoutOptionalTitle() throws Exception {
        MockHttpSession session=user();
        String content="第一段经验\n保留原来的段落。";
        JsonNode created=write(post("/api/me/collections/drafts"),session,Map.of("name","快速开始","format","CUSTOM","templateKey","RENTING","initialContent",content),201);
        assertEquals(6,created.get("sections").size());
        JsonNode note=created.get("sections").get(0).get("entries").get(0);
        assertEquals(content,note.get("body").asText());assertEquals("",note.get("title").asText());
        publish(session,created);
        mvc.perform(get("/api/collections/"+created.get("id").asLong()+"/outline")).andExpect(jsonPath("$.sections[0].entries[0].body").value(content));
        long before=jdbc.queryForObject("select count(*) from community_collections",Long.class);
        write(post("/api/me/collections/drafts"),session,Map.of("name","不能截断","format","CUSTOM","initialContent","x".repeat(4001)),400);
        assertEquals(before,jdbc.queryForObject("select count(*) from community_collections",Long.class));
    }

    @Test void copyAndImportCannotCrossOwnersAndImportedHtmlIsOnlyText() throws Exception {
        MockHttpSession owner=user(),other=user();ObjectNode doc=draft(owner);long id=doc.get("id").asLong();
        mvc.perform(get("/api/me/collections/"+id+"/preview").session(other)).andExpect(status().isForbidden());
        write(post("/api/me/collections/"+id+"/duplicate"),other,Map.of("structureOnly",false),403);
        write(post("/api/me/collections/"+id+"/imports"),other,Map.of("idempotencyKey",UUID.randomUUID().toString(),"document",doc),403);
        JsonNode copied=write(post("/api/me/collections/"+id+"/duplicate"),owner,Map.of("structureOnly",true),201);
        assertNotEquals(id,copied.get("id").asLong());assertEquals("DRAFT",copied.get("status").asText());assertEquals(0,copied.get("sections").get(0).get("entries").size());
        JsonNode full=write(post("/api/me/collections/"+id+"/duplicate"),owner,Map.of("structureOnly",false),201);assertEquals(doc.get("sections").get(0).get("entries").get(0).get("body"),full.get("sections").get(0).get("entries").get(0).get("body"));
        mvc.perform(post("/api/me/collection-imports/preview").session(owner).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        JsonNode parsed=parse(owner,"<script>alert(1)</script>\n| 表格 | 内容 |");assertTrue(parsed.get("warnings").size()>0);assertTrue(parsed.get("document").get("sections").get(0).get("entries").get(0).get("body").asText().contains("<script>"));
    }

    @Test void legacyWritesUseRevisionsAndNeverChangePublication() throws Exception {
        MockHttpSession session=user();ObjectNode doc=draft(session);long id=doc.get("id").asLong();publish(session,doc);
        JsonNode article=write(post("/api/posts"),session,Map.of("type","ARTICLE","title","文章","summary","摘要","body","正文"),201);
        ObjectNode before=current(session,id);String url="/api/me/collections/"+id+"/posts/"+article.get("id").asLong();
        write(put(url).param("revision",before.get("revision").asText()),session,null,200);
        write(put("/api/me/collections/"+id+"/draft"),session,before,409);
        mvc.perform(get("/api/collections/"+id+"/posts")).andExpect(jsonPath("$.total").value(0));
        publish(session,current(session,id));mvc.perform(get("/api/collections/"+id+"/posts")).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].body").doesNotExist());
        write(delete(url).param("revision",current(session,id).get("revision").asText()),session,null,200);
        mvc.perform(get("/api/collections/"+id+"/posts")).andExpect(jsonPath("$.total").value(1));
    }
}
