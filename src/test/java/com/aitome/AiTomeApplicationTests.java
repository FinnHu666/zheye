package com.aitome;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aitome.content.Comment;
import com.aitome.content.CommentRepository;
import com.aitome.content.CommunityCollection;
import com.aitome.content.CollectionRepository;
import com.aitome.content.ContentPost;
import com.aitome.content.ContentPostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class AiTomeApplicationTests {
    @Autowired com.aitome.content.CollectionPublicationService publicationService;
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired CommentRepository commentRepository;
    @Autowired ContentPostRepository contentPostRepository; @Autowired CollectionRepository collectionRepository; @Autowired JdbcTemplate jdbc;
    @Test void homeAndPublicListsAreAvailable() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"app\"")));
        mvc.perform(get("/collections")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/vps")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/api/posts")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.total").isNumber());
        mvc.perform(get("/api/collections")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.total").isNumber());
        mvc.perform(get("/api/vps")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    @Test void publicPostsArePagedFilteredAndStableForMatchingTimestamps() throws Exception {
        long authorId = createTestUser("post-pages");
        long baselineTopics = jdbc.queryForObject("select count(*) from content_posts where type='TOPIC'", Long.class);
        String prefix = "page-topic-" + UUID.randomUUID().toString().replace("-", "");
        List<ContentPost> saved = contentPostRepository.saveAll(java.util.stream.IntStream.range(0, 55)
                .mapToObj(i -> new ContentPost(ContentPost.Type.TOPIC, prefix + "-" + i, "分页测试摘要", "分页测试正文", authorId, "分页测试用户"))
                .toList());
        jdbc.update("update content_posts set created_at=? where title like ?",
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(300)), prefix + "%");
        List<Long> expectedIds = saved.stream().map(ContentPost::getId).sorted(java.util.Comparator.reverseOrder()).toList();
        long expectedTotal = baselineTopics + saved.size();

        List<Long> actualIds = new java.util.ArrayList<>();
        int pageCount = (int) ((expectedTotal + 49) / 50);
        for (int page = 0; page < pageCount; page++) {
            String response = mvc.perform(get("/api/posts").param("type", "TOPIC").param("page", String.valueOf(page)).param("size", "50"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(page)).andExpect(jsonPath("$.size").value(50))
                    .andExpect(jsonPath("$.total").value(expectedTotal))
                    .andExpect(jsonPath("$.items.length()").value(Math.min(50, expectedTotal - (long) page * 50)))
                    .andReturn().getResponse().getContentAsString();
            if (page == 0) org.junit.jupiter.api.Assertions.assertEquals("TOPIC", json.readTree(response).path("items").get(0).path("type").asText());
            actualIds.addAll(itemIds(response));
        }
        org.junit.jupiter.api.Assertions.assertEquals(expectedTotal, actualIds.size(), "post pages must cover the reported total");
        org.junit.jupiter.api.Assertions.assertEquals(actualIds.size(), new java.util.HashSet<>(actualIds).size(), "post pages must not overlap");
        org.junit.jupiter.api.Assertions.assertEquals(expectedIds, actualIds.subList(0, expectedIds.size()), "matching timestamps must use ID as a stable tie-breaker");

        String cappedPage = mvc.perform(get("/api/posts").param("type", "TOPIC").param("page", "0").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50)).andExpect(jsonPath("$.items.length()").value(50))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(expectedIds.subList(0, 50), itemIds(cappedPage));
        mvc.perform(get("/api/posts").param("type", "TOPIC").param("page", "-1").param("size", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/posts").param("type", "TOPIC").param("page", "10001").param("size", "50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(10000)).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(expectedTotal));
        mvc.perform(get("/api/posts").param("type", "ARTICLE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].type").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("ARTICLE"))));
        mvc.perform(get("/api/posts/" + expectedIds.get(0))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(expectedIds.get(0)));
    }

    @Test void publicPostListOmitsLongBodyUntilDetailIsRequested() throws Exception {
        long authorId = createTestUser("post-summary");
        String body = "正文".repeat(10_000);
        ContentPost saved = contentPostRepository.saveAndFlush(new ContentPost(
                ContentPost.Type.ARTICLE, "长正文摘要验收", "列表只需摘要字段", body, authorId, "正文验收用户"));
        jdbc.update("update content_posts set created_at=? where id=?",
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(600)), saved.getId());

        byte[] listBytes = mvc.perform(get("/api/posts").param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(saved.getId()))
                .andExpect(jsonPath("$.items[0].summary").value("列表只需摘要字段"))
                .andExpect(jsonPath("$.items[0].body").doesNotExist())
                .andReturn().getResponse().getContentAsByteArray();

        byte[] detailBytes = mvc.perform(get("/api/posts/" + saved.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertTrue(body.equals(json.readTree(detailBytes).path("body").asText()),
                "detail endpoint should preserve the full body");
        org.junit.jupiter.api.Assertions.assertTrue(listBytes.length < detailBytes.length,
                "summary listing should transfer fewer bytes than a full long-body detail");
    }

    @Test void collectionAndPersonalPostListsArePagedSummaries() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie csrf = csrfCookie();
        String userResponse = mvc.perform(post("/api/auth/register").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", uniqueEmail("summary-pages"), "password", "password123", "displayName", "列表摘要用户"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long authorId = json.readTree(userResponse).get("id").asLong();

        String prefix = "collection-pages-" + UUID.randomUUID().toString().replace("-", "");
        String longBody = "正文".repeat(10_000);
        List<ContentPost> saved = contentPostRepository.saveAll(java.util.stream.IntStream.range(0, 55)
                .mapToObj(i -> new ContentPost(ContentPost.Type.TOPIC, prefix + "-" + i, "专题分页摘要",
                        i == 54 ? longBody : "短正文", authorId, "列表摘要用户"))
                .toList());
        java.sql.Timestamp sharedCreatedAt = java.sql.Timestamp.from(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        jdbc.update("update content_posts set created_at=? where title like ?", sharedCreatedAt, prefix + "%");
        CommunityCollection collection = collectionRepository.save(new CommunityCollection("摘要分页专题-" + prefix,
                "验证专题内容分页和投影查询", "列表摘要用户", 0, authorId));
        jdbc.update("insert into collection_sections(collection_id,title,description,sort_order,created_at,updated_at) values(?,'专题内容','',0,current_timestamp,current_timestamp)", collection.getId());
        long sectionId = jdbc.queryForObject("select id from collection_sections where collection_id=? and sort_order=0", Long.class, collection.getId());
        List<Long> orderedFixtureIds = saved.stream().map(ContentPost::getId).sorted(java.util.Comparator.reverseOrder()).toList();
        for (int index = 0; index < orderedFixtureIds.size(); index++) {
            jdbc.update("insert into collection_entries(collection_id,section_id,kind,sort_order,title,annotation,body,post_id,external_url,created_at,updated_at) values(?,?,'POST',?,null,'',null,?,null,?,?)",
                    collection.getId(), sectionId, index, orderedFixtureIds.get(index), sharedCreatedAt, sharedCreatedAt);
        }

        publicationService.publishSnapshot(collection.getId());
        List<Long> expectedIds = saved.stream().map(ContentPost::getId).sorted(java.util.Comparator.reverseOrder()).toList();
        List<Long> collectionIds = new java.util.ArrayList<>();
        int pageCount = (saved.size() + 19) / 20;
        for (int page = 0; page < pageCount; page++) {
            String response = mvc.perform(get("/api/collections/" + collection.getId() + "/posts")
                            .param("page", String.valueOf(page)).param("size", "20"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(page)).andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.total").value(saved.size()))
                    .andExpect(jsonPath("$.items.length()").value(Math.min(20, saved.size() - page * 20)))
                    .andExpect(jsonPath("$.items[0].body").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            collectionIds.addAll(itemIds(response));
        }
        org.junit.jupiter.api.Assertions.assertEquals(expectedIds, collectionIds,
                "collection posts should be stable, complete, and non-overlapping across pages");

        String cappedCollectionPage = mvc.perform(get("/api/collections/" + collection.getId() + "/posts").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50)).andExpect(jsonPath("$.items.length()").value(50))
                .andExpect(jsonPath("$.items[0].body").doesNotExist()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(expectedIds.subList(0, 50), itemIds(cappedCollectionPage));

        List<Long> personalIds = new java.util.ArrayList<>();
        for (int page = 0; page < pageCount; page++) {
            String response = mvc.perform(get("/api/me/posts").session(session).param("page", String.valueOf(page)).param("size", "20"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(page)).andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.hasMore").value(page < pageCount - 1))
                    .andExpect(jsonPath("$.items.length()").value(Math.min(20, saved.size() - page * 20)))
                    .andExpect(jsonPath("$.items[0].body").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            personalIds.addAll(itemIds(response));
        }
        org.junit.jupiter.api.Assertions.assertEquals(expectedIds, personalIds,
                "personal post pages should preserve their existing stable order and not overlap");
        mvc.perform(get("/api/posts/" + expectedIds.get(0))).andExpect(status().isOk()).andExpect(jsonPath("$.body").value(longBody));
        mvc.perform(get("/api/collections/999999999/posts")).andExpect(status().isNotFound());
    }

    @Test void publicCollectionsArePagedAndStableForMatchingTimestamps() throws Exception {
        long baseline = jdbc.queryForObject("select count(*) from collection_publications p join community_collections c on c.id=p.collection_id where c.status='PUBLISHED'",Long.class);
        String prefix = "分页专题-" + UUID.randomUUID().toString().replace("-", "");
        List<CommunityCollection> saved = collectionRepository.saveAll(java.util.stream.IntStream.range(0, 55)
                .mapToObj(i -> new CommunityCollection(prefix + "-" + i, "分页目录测试", "分页测试用户", 0))
                .toList());
        jdbc.update("update community_collections set updated_at=? where name like ?",
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(300)), prefix + "%");
        saved.forEach(c -> publicationService.publishSnapshot(c.getId()));
        List<Long> expectedIds = saved.stream().map(CommunityCollection::getId).sorted(java.util.Comparator.reverseOrder()).toList();

        List<Long> actualIds = new java.util.ArrayList<>();
        long expectedTotal = baseline + saved.size();
        int pageCount = (int) ((expectedTotal + 49) / 50);
        for (int page = 0; page < pageCount; page++) {
            String response = mvc.perform(get("/api/collections").param("page", String.valueOf(page)).param("size", "50"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(page)).andExpect(jsonPath("$.size").value(50))
                    .andExpect(jsonPath("$.total").value(expectedTotal))
                    .andExpect(jsonPath("$.items.length()").value(Math.min(50, expectedTotal - (long) page * 50)))
                    .andReturn().getResponse().getContentAsString();
            actualIds.addAll(itemIds(response));
        }
        org.junit.jupiter.api.Assertions.assertEquals(expectedTotal, actualIds.size(), "collection pages must cover the reported total");
        org.junit.jupiter.api.Assertions.assertEquals(actualIds.size(), new java.util.HashSet<>(actualIds).size(), "collection pages must not overlap");
        org.junit.jupiter.api.Assertions.assertEquals(expectedIds, actualIds.subList(0, expectedIds.size()), "matching timestamps must use ID as a stable tie-breaker");

        String cappedPage = mvc.perform(get("/api/collections").param("page", "0").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50)).andExpect(jsonPath("$.items.length()").value(50))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(expectedIds.subList(0, 50), itemIds(cappedPage));
        mvc.perform(get("/api/collections").param("page", "-4").param("size", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/collections").param("page", "10001").param("size", "50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(10000)).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(baseline + saved.size()));
    }
    @Test void publicSiteStatsReflectDatabaseCounts() throws Exception {
        String response = mvc.perform(get("/api/stats")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var stats = json.readTree(response);
        org.junit.jupiter.api.Assertions.assertEquals(jdbc.queryForObject("select count(*) from user_accounts", Long.class), stats.get("creatorCount").asLong());
        org.junit.jupiter.api.Assertions.assertEquals(jdbc.queryForObject("select count(*) from content_posts", Long.class), stats.get("contentCount").asLong());
        org.junit.jupiter.api.Assertions.assertEquals(jdbc.queryForObject("select count(*) from community_collections c join collection_publications p on p.collection_id=c.id where c.status='PUBLISHED'", Long.class), stats.get("collectionCount").asLong());
    }
    @Test void userCanRegisterPublishAndComment() throws Exception {
        MockHttpSession session=new MockHttpSession();
        String email = uniqueEmail("new");
        Cookie csrf = csrfCookie();
        mvc.perform(post("/api/auth/register").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email",email,"password","password123","displayName","新用户"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.displayName").value("新用户"));
        csrf = csrfCookie();
        String response=mvc.perform(post("/api/posts").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("type","ARTICLE","title","测试文章","summary","这是测试摘要","body","正文内容"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.authorName").value("新用户")).andReturn().getResponse().getContentAsString();
        long id=json.readTree(response).get("id").asLong();
        csrf = csrfCookie();
        mvc.perform(post("/api/posts/"+id+"/comments").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"第一条讨论\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.body").value("第一条讨论"));
        mvc.perform(get("/api/posts/"+id+"/comments").param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].body").value("第一条讨论")).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/posts/"+id+"/comments").param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/posts/"+id+"/comments").param("page", "-1").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/posts/999999999/comments")).andExpect(status().isNotFound());
    }

    @Test void commentPagesAreBoundedAndDoNotOverlap() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String email = uniqueEmail("comment-pages");
        Cookie csrf = csrfCookie();
        String userResponse = mvc.perform(post("/api/auth/register").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", "password123", "displayName", "分页用户"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long userId = json.readTree(userResponse).get("id").asLong();
        csrf = csrfCookie();
        String postResponse = mvc.perform(post("/api/posts").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", "TOPIC", "title", "大量讨论分页", "summary", "验证批量评论分页", "body", "正文"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long postId = json.readTree(postResponse).get("id").asLong();
        List<Comment> rows = java.util.stream.IntStream.range(0, 55)
                .mapToObj(i -> new Comment(postId, userId, "分页用户", "分页评论 " + i)).toList();
        rows = commentRepository.saveAll(rows);
        jdbc.update("update comments set created_at=? where post_id=?", java.sql.Timestamp.from(java.time.Instant.parse("2026-09-29T00:00:00Z")), postId);

        String firstPage = mvc.perform(get("/api/posts/" + postId + "/comments").param("page", "0").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.total").value(55)).andExpect(jsonPath("$.items.length()").value(50))
                .andReturn().getResponse().getContentAsString();
        String secondPage = mvc.perform(get("/api/posts/" + postId + "/comments").param("page", "1").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.total").value(55)).andExpect(jsonPath("$.items.length()").value(5))
                .andReturn().getResponse().getContentAsString();
        List<Long> firstIds = json.readTree(firstPage).get("items").findValuesAsText("id").stream().map(Long::valueOf).toList();
        List<Long> secondIds = json.readTree(secondPage).get("items").findValuesAsText("id").stream().map(Long::valueOf).toList();
        org.junit.jupiter.api.Assertions.assertTrue(java.util.Collections.disjoint(firstIds, secondIds), "comment pages must not overlap");
        org.junit.jupiter.api.Assertions.assertEquals(55, firstIds.size() + secondIds.size());
        List<Long> expectedIds = rows.stream().map(Comment::getId).sorted().toList();
        org.junit.jupiter.api.Assertions.assertEquals(expectedIds, java.util.stream.Stream.concat(firstIds.stream(), secondIds.stream()).toList(),
                "comments with matching timestamps must retain deterministic ID order across pages");
    }
    @Test void anonymousCannotPublish() throws Exception {
        mvc.perform(get("/api/me/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/collections")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"TOPIC\",\"title\":\"标题\",\"summary\":\"摘要\",\"body\":\"正文\"}"))
                .andExpect(status().isForbidden());
        Cookie csrf = csrfCookie();
        mvc.perform(post("/api/posts").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"TOPIC\",\"title\":\"标题\",\"summary\":\"摘要\",\"body\":\"正文\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void userCanEditProfileAndSelectCollections() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String email = uniqueEmail("workspace");
        Cookie csrf = csrfCookie();
        mvc.perform(post("/api/auth/register").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", "password123", "displayName", "专题用户"))))
                .andExpect(status().isCreated());

        csrf = csrfCookie();
        mvc.perform(patch("/api/me/profile").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("displayName", "专题作者", "bio", "整理开发经验"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("专题作者")).andExpect(jsonPath("$.bio").value("整理开发经验"));

        csrf = csrfCookie();
        String collectionJson = mvc.perform(post("/api/collections").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "我的专题", "description", "个人整理的专题目录"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long collectionId = json.readTree(collectionJson).get("id").asLong();

        csrf = csrfCookie();
        String postJson = mvc.perform(post("/api/posts").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("type", "ARTICLE", "title", "自己的专题内容", "summary", "用于验证专题关联", "body", "专题正文"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long postId = json.readTree(postJson).get("id").asLong();

        csrf = csrfCookie();
        mvc.perform(put("/api/me/collections/" + collectionId + "/posts/" + postId).param("revision", "0").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.itemCount").value(1));
        csrf = csrfCookie();
        mvc.perform(put("/api/me/collections/" + collectionId + "/posts/1").param("revision", "1").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden());
        publishLegacy(collectionId, session);
        mvc.perform(get("/api/collections/" + collectionId + "/posts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(postId)).andExpect(jsonPath("$.items[0].body").doesNotExist());

        csrf = csrfCookie();
        mvc.perform(put("/api/me/collections/selected/" + collectionId).session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.items[0].name").value("我的专题"));
        mvc.perform(get("/api/me/collections").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].ownerName").value("专题作者"));
        mvc.perform(get("/api/me/profile").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.collectionCount").value(1)).andExpect(jsonPath("$.postCount").value(1));
    }

    @Test void privateCollectionsAndContentAreIsolatedBetweenUsers() throws Exception {
        String ownerEmail = uniqueEmail("owner");
        MockHttpSession ownerSession = new MockHttpSession();
        Cookie csrf = csrfCookie();
        String ownerResponse = mvc.perform(post("/api/auth/register").session(ownerSession).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", ownerEmail, "password", "password123", "displayName", "隔离用户甲"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long ownerId = json.readTree(ownerResponse).get("id").asLong();

        csrf = csrfCookie();
        String firstCollectionJson = mvc.perform(post("/api/collections").session(ownerSession).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "甲的专题一", "description", "私有管理权验证"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long firstCollectionId = json.readTree(firstCollectionJson).get("id").asLong();

        csrf = csrfCookie();
        String secondCollectionJson = mvc.perform(post("/api/collections").session(ownerSession).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "甲的专题二", "description", "排序验证"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long secondCollectionId = json.readTree(secondCollectionJson).get("id").asLong();

        csrf = csrfCookie();
        String postJson = mvc.perform(post("/api/posts").session(ownerSession).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", "ARTICLE", "title", "甲的私有文章", "summary", "用户隔离验收", "body", "隔离测试正文"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long ownerPostId = json.readTree(postJson).get("id").asLong();

        csrf = csrfCookie();
        mvc.perform(put("/api/me/collections/" + firstCollectionId + "/posts/" + ownerPostId).param("revision", "0").session(ownerSession).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.itemCount").value(1));
        publishLegacy(firstCollectionId, ownerSession);
        publishLegacy(secondCollectionId, ownerSession);
        csrf = csrfCookie();
        mvc.perform(put("/api/me/collections/selected/" + firstCollectionId).session(ownerSession).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        csrf = csrfCookie();
        mvc.perform(put("/api/me/collections/selected/" + secondCollectionId).session(ownerSession).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        csrf = csrfCookie();
        mvc.perform(patch("/api/me/collections/selected/order").session(ownerSession).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("version", 2, "collectionIds", List.of(secondCollectionId, firstCollectionId)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3)).andExpect(jsonPath("$.items[0].id").value(secondCollectionId));
        csrf = csrfCookie();
        mvc.perform(delete("/api/me/collections/selected/" + firstCollectionId).session(ownerSession).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(4)).andExpect(jsonPath("$.items.length()").value(1));

        String otherEmail = uniqueEmail("other");
        MockHttpSession otherSession = new MockHttpSession();
        csrf = csrfCookie();
        String otherResponse = mvc.perform(post("/api/auth/register").session(otherSession).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", otherEmail, "password", "password123", "displayName", "隔离用户乙"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long otherId = json.readTree(otherResponse).get("id").asLong();

        mvc.perform(get("/api/me/profile").session(otherSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(otherId)).andExpect(jsonPath("$.displayName").value("隔离用户乙"))
                .andExpect(jsonPath("$.postCount").value(0)).andExpect(jsonPath("$.collectionCount").value(0)).andExpect(jsonPath("$.selectedCollectionCount").value(0));
        mvc.perform(get("/api/me/posts").session(otherSession)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/me/collections").session(otherSession)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/me/collections/selected").session(otherSession)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));

        csrf = csrfCookie();
        mvc.perform(patch("/api/me/profile").session(otherSession).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("id", ownerId, "userId", ownerId, "displayName", "隔离用户乙", "bio", "只能更新自己的资料"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(otherId)).andExpect(jsonPath("$.displayName").value("隔离用户乙"));

        csrf = csrfCookie();
        mvc.perform(put("/api/me/collections/" + firstCollectionId + "/posts/" + ownerPostId).param("revision", "0").session(otherSession).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden());
        csrf = csrfCookie();
        mvc.perform(delete("/api/me/collections/selected/" + firstCollectionId).session(otherSession).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));

        mvc.perform(get("/api/me/profile").session(ownerSession)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ownerId))
                .andExpect(jsonPath("$.postCount").value(1)).andExpect(jsonPath("$.collectionCount").value(2)).andExpect(jsonPath("$.selectedCollectionCount").value(1));
        mvc.perform(get("/api/me/collections/selected").session(ownerSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(secondCollectionId));

        MockHttpSession loginSession = new MockHttpSession();
        csrf = csrfCookie();
        mvc.perform(post("/api/auth/login").session(loginSession).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", ownerEmail, "password", "password123"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ownerId));
        mvc.perform(get("/api/me/profile").session(loginSession)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ownerId));
    }

    @Test void structuredCollectionWorkspaceSupportsDraftSavePublishAndIsolation() throws Exception {
        MockHttpSession owner = new MockHttpSession();
        Cookie csrf = csrfCookie();
        mvc.perform(post("/api/auth/register").session(owner).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", uniqueEmail("structured-owner"), "password", "password123", "displayName", "路线作者"))))
                .andExpect(status().isCreated());

        csrf = csrfCookie();
        String postJson = mvc.perform(post("/api/posts").session(owner).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", "ARTICLE", "title", "签约检查清单", "summary", "签约前逐项确认", "body", "核对合同与房屋状态"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long postId = json.readTree(postJson).get("id").asLong();

        csrf = csrfCookie();
        String draftJson = mvc.perform(post("/api/me/collections/drafts").session(owner).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "第一次租房路线", "description", "从准备到入住的完整步骤", "format", "GUIDE", "audience", "第一次租房的人", "goal", "独立完成租房"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.sections.length()").value(4)).andReturn().getResponse().getContentAsString();
        long collectionId = json.readTree(draftJson).get("id").asLong();
        long revision = json.readTree(draftJson).get("revision").asLong();

        mvc.perform(get("/api/collections/" + collectionId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/collections/" + collectionId + "/outline")).andExpect(status().isNotFound());

        var saveBody = json.createObjectNode();
        saveBody.put("revision", revision); saveBody.put("name", "第一次租房路线"); saveBody.put("description", "从准备到入住的完整步骤");
        saveBody.put("format", "GUIDE"); saveBody.put("audience", "第一次租房的人"); saveBody.put("goal", "独立完成租房");
        var sections = saveBody.putArray("sections");
        var prepare = sections.addObject(); prepare.put("title", "签约前"); prepare.put("description", "先收集信息再判断");
        var entries = prepare.putArray("entries");
        entries.addObject().put("kind", "NOTE").put("title", "明确预算").put("body", "把房租、通勤和押金一起计算").put("annotation", "先确定边界");
        entries.addObject().put("kind", "POST").put("postId", postId).put("annotation", "签约时使用");
        entries.addObject().put("kind", "LINK").put("title", "公共服务入口").put("externalUrl", "https://example.com/renting").put("annotation", "核验公开信息");

        csrf = csrfCookie();
        String savedJson = mvc.perform(put("/api/me/collections/" + collectionId + "/draft").session(owner).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(saveBody)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sections[0].entries.length()").value(3))
                .andExpect(jsonPath("$.revision").value(revision + 1)).andReturn().getResponse().getContentAsString();
        long savedRevision = json.readTree(savedJson).get("revision").asLong();

        csrf = csrfCookie();
        mvc.perform(put("/api/me/collections/" + collectionId + "/draft").session(owner).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(saveBody)))
                .andExpect(status().isConflict());

        MockHttpSession other = new MockHttpSession(); csrf = csrfCookie();
        mvc.perform(post("/api/auth/register").session(other).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", uniqueEmail("structured-other"), "password", "password123", "displayName", "其他用户"))))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/me/collections/" + collectionId + "/draft").session(other)).andExpect(status().isForbidden());

        csrf = csrfCookie();
        mvc.perform(post("/api/me/collections/" + collectionId + "/publish").session(owner).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("revision", savedRevision))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        mvc.perform(get("/api/collections/" + collectionId + "/outline"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.collection.name").value("第一次租房路线"))
                .andExpect(jsonPath("$.sections[0].entries[0].kind").value("NOTE"))
                .andExpect(jsonPath("$.sections[0].entries[1].postId").value(postId))
                .andExpect(jsonPath("$.sections[0].entries[2].externalUrl").value("https://example.com/renting"));
    }

    private void publishLegacy(long id, MockHttpSession session) throws Exception {
        var d = json.readTree(mvc.perform(get("/api/me/collections/"+id+"/draft").session(session)).andReturn().getResponse().getContentAsString());
        if (d.get("sections").get(0).get("entries").isEmpty()) {
            var entries = (com.fasterxml.jackson.databind.node.ArrayNode) d.get("sections").get(0).get("entries");
            entries.add(json.valueToTree(Map.of("kind","NOTE","title","介绍","body","测试公开内容")));
            Cookie token=csrfCookie();
            d=json.readTree(mvc.perform(put("/api/me/collections/"+id+"/draft").session(session).cookie(token).header("X-XSRF-TOKEN",token.getValue()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(d))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        }
        Cookie token=csrfCookie();
        mvc.perform(post("/api/me/collections/"+id+"/publish").session(session).cookie(token).header("X-XSRF-TOKEN",token.getValue()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("revision",d.get("revision").asLong())))).andExpect(status().isOk());
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "") + "@aitome.dev";
    }

    private long createTestUser(String prefix) throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie csrf = csrfCookie();
        String response = mvc.perform(post("/api/auth/register").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", uniqueEmail(prefix), "password", "password123", "displayName", "分页测试用户"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private List<Long> itemIds(String response) throws Exception {
        var items = json.readTree(response).get("items");
        List<Long> ids = new java.util.ArrayList<>();
        items.forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    private Cookie csrfCookie() throws Exception {
        Cookie cookie = mvc.perform(get("/api/auth/csrf")).andExpect(status().isNoContent()).andReturn().getResponse().getCookie("XSRF-TOKEN");
        if (cookie == null) throw new AssertionError("CSRF cookie was not initialized");
        return cookie;
    }
}
