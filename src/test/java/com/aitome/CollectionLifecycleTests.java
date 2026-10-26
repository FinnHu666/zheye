package com.aitome;

import com.aitome.content.CollectionScheduler;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"finns.collections.scheduling-enabled=true","finns.collections.layouts-enabled=true"}) @AutoConfigureMockMvc
class CollectionLifecycleTests {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json; @Autowired CollectionScheduler scheduler;
    @Autowired com.aitome.review.ReviewService review;
    @Autowired com.aitome.content.CollectionCoverController covers;
    @BeforeEach void rules(){TestReviewConfig.reset(jdbc);}
    MockHttpSession user() throws Exception {var s=new MockHttpSession();TestUsers.registerAndLogin(mvc,jdbc,s,UUID.randomUUID()+"@finns.dev","password123","专题测试").andExpect(status().isOk());return s;}
    JsonNode write(MockHttpServletRequestBuilder b,MockHttpSession s,Object body,int expected)throws Exception{
        Cookie token=mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");b.session(s).cookie(token).header("X-XSRF-TOKEN",token.getValue());
        if(body!=null)b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String text=mvc.perform(b).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);return text.isBlank()?json.createObjectNode():json.readTree(text);
    }
    ObjectNode draft(MockHttpSession s)throws Exception{return (ObjectNode)write(post("/api/me/collections/drafts"),s,Map.of("name","生命周期","format","CUSTOM","templateKey","BLANK","audience","开发者","goal","技能提升","description","专题简介","initialContent","公开笔记"),201);}
    ObjectNode current(MockHttpSession s,long id)throws Exception{return (ObjectNode)json.readTree(mvc.perform(get("/api/me/collections/"+id+"/draft").session(s)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
    JsonNode publish(MockHttpSession s,JsonNode d,Instant due)throws Exception{return publish(s,d,due,200);}
    JsonNode publish(MockHttpSession s,JsonNode d,Instant due,int expected)throws Exception{var body=new HashMap<String,Object>();body.put("revision",d.get("revision").asLong());body.put("requestId",UUID.randomUUID().toString());if(due!=null)body.put("scheduledAt",due.toString());return write(post("/api/me/collections/"+d.get("id").asLong()+"/publish"),s,body,expected);}
    ObjectNode metadata(){return json.valueToTree(Map.of("tags",List.of(" AI ","ai","工具"),"category","engineering","seoTitle","<专题 & 学习>","audiences",List.of("开发者"),"sectionLayouts",List.of("CARDS")));}

    @Test void metadataIsValidatedAndFrozenUntilExplicitPublication()throws Exception{
        var s=user();var d=draft(s);long id=d.get("id").asLong();d.set("metadata",metadata());
        d=(ObjectNode)write(put("/api/me/collections/"+id+"/draft"),s,d,200);assertEquals(2,d.path("metadata").path("tags").size());publish(s,d,null);
        d=current(s,id);((ObjectNode)d.get("metadata")).put("seoTitle","私人改稿");d.put("name","私人标题");write(put("/api/me/collections/"+id+"/draft"),s,d,200);
        mvc.perform(get("/api/collections/"+id+"/outline")).andExpect(jsonPath("$.metadata.seoTitle").value("<专题 & 学习>"));
        String page=mvc.perform(get("/collections/"+id)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertTrue(page.contains("&lt;专题 &amp; 学习&gt;"));assertFalse(page.contains("私人标题"));assertEquals(1,page.split("name=\"description\"",-1).length-1);assertTrue(page.contains("content=\"专题简介\""));
        String list=mvc.perform(get("/api/collections?category=engineering")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertTrue(list.contains("\"id\":"+id));
        d=current(s,id);((ObjectNode)d.get("metadata")).put("category","unknown");write(put("/api/me/collections/"+id+"/draft"),s,d,400);
        ((ObjectNode)d.get("metadata")).put("category","engineering");((ObjectNode)d.get("metadata")).set("audiences",json.valueToTree(List.of("无效人群")));write(put("/api/me/collections/"+id+"/draft"),s,d,400);
    }

    @Test void withdrawalRetainsSelectionsAndOriginalPublicationTime()throws Exception{
        var author=user();var reader=user();var d=draft(author);long id=d.get("id").asLong();publish(author,d,null);String first=current(author,id).get("publishedAt").asText();
        write(put("/api/me/collections/selected/"+id),reader,null,200);
        d=current(author,id);write(post("/api/me/collections/"+id+"/archive"),author,Map.of("revision",d.get("revision").asLong()),200);
        assertEquals(1,jdbc.queryForObject("select count(*) from user_collection_selections where collection_id=?",Integer.class,id));
        mvc.perform(get("/api/collections/"+id+"/outline")).andExpect(status().isNotFound());mvc.perform(get("/api/me/collections/selected").session(reader)).andExpect(jsonPath("$.items.length()").value(0));
        publish(author,current(author,id),null);assertEquals(first,current(author,id).get("publishedAt").asText());mvc.perform(get("/api/me/collections/selected").session(reader)).andExpect(jsonPath("$.items.length()").value(1));
        write(delete("/api/me/collections/"+id+"/draft?revision="+current(author,id).get("revision").asLong()),author,null,409);
    }

    @Test void unpublishedDraftDeletionChecksOwnerRevisionAndPendingSubmission()throws Exception{
        var s=user();var other=user();var d=draft(s);long id=d.get("id").asLong();
        write(delete("/api/me/collections/"+id+"/draft?revision=0"),other,null,403);write(delete("/api/me/collections/"+id+"/draft?revision=9"),s,null,409);
        write(delete("/api/me/collections/"+id+"/draft?revision=0"),s,null,204);assertEquals(0,jdbc.queryForObject("select count(*) from community_collections where id=?",Integer.class,id));
    }

    @Test void scheduledPublicationFreezesContentAndCancellationPreventsReappearance()throws Exception{
        var s=user();var d=draft(s);long id=d.get("id").asLong();publish(s,d,Instant.now().plusSeconds(600));mvc.perform(get("/api/collections/"+id)).andExpect(status().isNotFound());
        d=current(s,id);d.put("name","不能自动公开的改稿");write(put("/api/me/collections/"+id+"/draft"),s,d,200);
        jdbc.update("update community_collections set scheduled_at=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),id);scheduler.tick();scheduler.tick();
        mvc.perform(get("/api/collections/"+id)).andExpect(jsonPath("$.name").value("生命周期"));
        d=current(s,id);publish(s,d,Instant.now().plusSeconds(600));write(post("/api/me/collections/"+id+"/cancel"),s,Map.of("revision",d.get("revision").asLong()),200);scheduler.tick();assertNull(current(s,id).get("scheduledAt").isNull()?null:current(s,id).get("scheduledAt"));
        mvc.perform(get("/api/collections/"+id)).andExpect(jsonPath("$.name").value("生命周期"));
    }

    @Test void scheduledPublicationRechecksDisabledAuthorsAndInvalidReferences()throws Exception{
        var s=user();var d=draft(s);long id=d.get("id").asLong();publish(s,d,Instant.now().plusSeconds(600));
        jdbc.update("update community_collections set scheduled_at=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),id);
        jdbc.update("update user_accounts set account_status='DISABLED' where id=(select owner_id from community_collections where id=?)",id);scheduler.tick();mvc.perform(get("/api/collections/"+id)).andExpect(status().isNotFound());
    }

    byte[] image()throws Exception{var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(320,240,BufferedImage.TYPE_INT_RGB),"PNG",bytes);return bytes.toByteArray();}
    JsonNode upload(MockHttpSession s,long id,byte[] bytes,String name,int expected)throws Exception{
        Cookie token=mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        String text=mvc.perform(multipart("/api/me/collections/"+id+"/cover").file(new MockMultipartFile("file",name,"image/png",bytes)).session(s).cookie(token).header("X-XSRF-TOKEN",token.getValue())).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);return json.readTree(text);
    }
    @Test void coversAreReencodedAndPrivateUntilTheirSnapshotIsPublished()throws Exception{
        var s=user();var other=user();var d=draft(s);long id=d.get("id").asLong();
        upload(s,id,"fake".getBytes(),"x.png",400);upload(s,id,image(),"x.jpg",400);upload(other,id,image(),"x.png",403);
        JsonNode c=upload(s,id,image(),"x.png",200);String url=c.get("url").asText();mvc.perform(get(url)).andExpect(status().isNotFound());mvc.perform(get(url).session(other)).andExpect(status().isNotFound());
        long otherId=(Long)other.getAttribute(com.aitome.user.AuthController.USER_ID);jdbc.update("update user_accounts set role_id=(select id from roles where system_kind='OWNER') where id=?",otherId);
        write(delete("/api/admin/collection-covers/"+c.get("coverId").asText()),other,Map.of("reason","不可越权处置未提交的私人封面"),404);
        byte[] encoded=mvc.perform(get(url).session(s)).andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse().getContentAsByteArray();var img=ImageIO.read(new java.io.ByteArrayInputStream(encoded));assertEquals(16*img.getHeight(),9*img.getWidth());
        var m=metadata();m.put("coverId",c.get("coverId").asText());d.set("metadata",m);d=(ObjectNode)write(put("/api/me/collections/"+id+"/draft"),s,d,200);publish(s,d,null);mvc.perform(get(url)).andExpect(status().isOk());
        write(delete("/api/admin/collection-covers/"+c.get("coverId").asText()),other,Map.of("reason","仅移除已发布封面"),204);mvc.perform(get(url)).andExpect(status().isNotFound());assertEquals(1,jdbc.queryForObject("select count(*) from review_audit_log where type='TAKE_DOWN' and result_message like '仅移除专题封面%'",Integer.class));
        jdbc.update("update community_collections set moderation_state='TAKEN_DOWN' where id=?",id);mvc.perform(get(url)).andExpect(status().isNotFound());
        JsonNode orphan=upload(s,id,image(),"unused.png",200);String unused=orphan.get("coverId").asText();jdbc.update("update collection_covers set created_at=? where id in (?,?)",java.sql.Timestamp.from(Instant.now().minusSeconds(172800)),unused,c.get("coverId").asText());covers.cleanup();assertEquals(0,jdbc.queryForObject("select count(*) from collection_covers where id=?",Integer.class,unused));assertEquals(1,jdbc.queryForObject("select count(*) from collection_covers where id=?",Integer.class,c.get("coverId").asText()));
    }

    @Test void duplicateAndAppendPreserveMetadataWithoutPublicationOrSchedule()throws Exception{
        var s=user();var d=draft(s);long id=d.get("id").asLong();d.set("metadata",metadata());d=(ObjectNode)write(put("/api/me/collections/"+id+"/draft"),s,d,200);publish(s,d,Instant.now().plusSeconds(600));
        var copy=write(post("/api/me/collections/"+id+"/duplicate"),s,Map.of("structureOnly",false),201);assertEquals("engineering",copy.path("metadata").path("category").asText());assertEquals("DRAFT",copy.get("status").asText());assertTrue(copy.get("scheduledAt").isNull());
        var parsed=write(post("/api/me/collection-imports/preview"),s,Map.of("text","# 补充\n新条目","preserveWhole",false),200);((ObjectNode)parsed.get("document")).put("revision",d.get("revision").asLong());
        var appended=write(post("/api/me/collections/"+id+"/imports"),s,Map.of("idempotencyKey",UUID.randomUUID().toString(),"document",parsed.get("document")),200);assertEquals("engineering",appended.path("metadata").path("category").asText());
    }

    long reviewer()throws Exception { var s=user();long id=(Long)s.getAttribute(com.aitome.user.AuthController.USER_ID);jdbc.update("update user_accounts set role_id=(select id from roles where system_kind='OWNER') where id=?",id);return id; }
    void rule(String word,String level){jdbc.update("insert into sensitive_rules(phrase,note,enabled,deleted,active_key,level,version,created_at,updated_at) values(?,'',true,false,?,?,1,current_timestamp,current_timestamp)",word,word,level);}

    @Test void reviewApprovesFrozenScheduledVersionAndHighReviewCannotBeCancelled()throws Exception{
        var s=user();long operator=reviewer();rule("midword","MEDIUM");rule("highword","HIGH");var d=draft(s);long id=d.get("id").asLong();d.put("goal","midword");d=(ObjectNode)write(put("/api/me/collections/"+id+"/draft"),s,d,200);
        var result=publish(s,d,Instant.now().plusSeconds(600),202);assertEquals("PENDING",result.get("outcome").asText());long task=jdbc.queryForObject("select max(id) from review_submissions where content_type='COLLECTION'",Long.class);long generation=jdbc.queryForObject("select generation from review_submissions where id=?",Long.class,task);
        d=current(s,id);d.put("name","审批后不能夹带的工作稿");write(put("/api/me/collections/"+id+"/draft"),s,d,200);review.decide(operator,task,"APPROVE",generation,"确认");mvc.perform(get("/api/collections/"+id)).andExpect(status().isNotFound());
        jdbc.update("update community_collections set scheduled_at=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),id);scheduler.tick();mvc.perform(get("/api/collections/"+id)).andExpect(jsonPath("$.name").value("生命周期"));
        d=current(s,id);d.put("goal","highword");d=(ObjectNode)write(put("/api/me/collections/"+id+"/draft"),s,d,200);assertEquals("AUTO_REJECTED",publish(s,d,null,202).get("outcome").asText());write(post("/api/me/collections/"+id+"/cancel"),s,Map.of("revision",d.get("revision").asLong()),409);
    }

    @Test void rejectedScheduledSubmissionIsClearedAndPendingDraftCannotBeDeleted()throws Exception{
        var s=user();long operator=reviewer();rule("midword","MEDIUM");var d=draft(s);long id=d.get("id").asLong();d.put("goal","midword");d=(ObjectNode)write(put("/api/me/collections/"+id+"/draft"),s,d,200);publish(s,d,Instant.now().plusSeconds(600),202);write(delete("/api/me/collections/"+id+"/draft?revision="+d.get("revision").asLong()),s,null,409);
        long task=jdbc.queryForObject("select max(id) from review_submissions where content_type='COLLECTION'",Long.class);long gen=jdbc.queryForObject("select generation from review_submissions where id=?",Long.class,task);review.decide(operator,task,"REJECT",gen,"需要修正");assertTrue(current(s,id).get("scheduledAt").isNull());
        write(delete("/api/me/collections/"+id+"/draft?revision="+d.get("revision").asLong()),s,null,204);
    }

    @Test void retryCannotRepublishWithdrawnContentAndReferenceCardsAreRedacted()throws Exception{
        var s=user();var d=draft(s);long id=d.get("id").asLong();String key=UUID.randomUUID().toString();var request=Map.of("revision",d.get("revision").asLong(),"requestId",key);
        write(post("/api/me/collections/"+id+"/publish"),s,request,200);write(post("/api/me/collections/"+id+"/publish"),s,request,200);d=current(s,id);write(post("/api/me/collections/"+id+"/archive"),s,Map.of("revision",d.get("revision").asLong()),200);write(post("/api/me/collections/"+id+"/publish"),s,request,200);mvc.perform(get("/api/collections/"+id)).andExpect(status().isNotFound());
        long author=(Long)s.getAttribute(com.aitome.user.AuthController.USER_ID);jdbc.update("insert into content_posts(type,title,summary,body,author_id,author_name,created_at,likes,moderation_state) values('ARTICLE','受保护标题','受保护摘要','受保护正文',?,'作者',current_timestamp,0,'VISIBLE')",author);long post=jdbc.queryForObject("select max(id) from content_posts where author_id=?",Long.class,author);
        d=current(s,id);ArrayNode entries=(ArrayNode)d.get("sections").get(0).get("entries");entries.add(json.valueToTree(Map.of("kind","POST","postId",post,"title","受保护自定义标题","annotation","受保护备注")));d=(ObjectNode)write(put("/api/me/collections/"+id+"/draft"),s,d,200);publish(s,d,null);jdbc.update("update content_posts set moderation_state='WITHDRAWN' where id=?",post);
        String response=mvc.perform(get("/api/collections/"+id+"/outline")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertFalse(response.contains("受保护"));assertTrue(response.contains("原文已不可用"));
    }

    @Test void oversizedAndMalformedImagesAreRejectedAndMissingDraftsAre404()throws Exception{
        var s=user();var d=draft(s);long id=d.get("id").asLong();var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4097,9,BufferedImage.TYPE_INT_RGB),"PNG",bytes);upload(s,id,bytes.toByteArray(),"x.png",400);upload(s,id,new byte[2*1024*1024+1],"x.png",400);
        write(delete("/api/me/collections/999999999/draft?revision=0"),s,null,404);write(post("/api/me/collections/999999999/cancel"),s,Map.of("revision",0),404);
    }

    @Test void approvalRacingWithdrawalCannotRestoreAnOldSubmission()throws Exception{
        var s=user();long operator=reviewer();rule("midword","MEDIUM");var d=draft(s);long id=d.get("id").asLong();d.put("goal","midword");d=(ObjectNode)write(put("/api/me/collections/"+id+"/draft"),s,d,200);publish(s,d,null,202);
        long task=jdbc.queryForObject("select max(id) from review_submissions where content_type='COLLECTION'",Long.class);long gen=jdbc.queryForObject("select generation from review_submissions where id=?",Long.class,task);long version=d.get("revision").asLong();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try{
            var approval=pool.submit(()->{start.await();try{review.decide(operator,task,"APPROVE",gen,"确认");}catch(com.aitome.common.ApiExceptionHandler.ApiProblem expected){}return true;});
            var withdrawal=pool.submit(()->{start.await();write(post("/api/me/collections/"+id+"/archive"),s,Map.of("revision",version),200);return true;});
            start.countDown();approval.get(15,java.util.concurrent.TimeUnit.SECONDS);withdrawal.get(15,java.util.concurrent.TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        mvc.perform(get("/api/collections/"+id)).andExpect(status().isNotFound());assertEquals("ARCHIVED",current(s,id).get("status").asText());
    }
}
