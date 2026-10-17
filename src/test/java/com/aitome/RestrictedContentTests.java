package com.aitome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** FINNS-11 验收 A06/A07：受限专题在各入口不泄漏，可见范围设置规则（契约 §6）。 */
@SpringBootTest @AutoConfigureMockMvc
class RestrictedContentTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    AccessTestSupport t;

    @BeforeEach void setUp() { t = new AccessTestSupport(mvc, json, jdbc); }

    @Test void restrictedCollectionIsHiddenEverywhereFromRolesWithoutPermission() throws Exception {
        var author = t.withRole("author", "TRUSTED");
        var member = t.withRole("reader", "MEMBER");
        var trusted = t.withRole("insider", "TRUSTED");
        long id = publishedCollection(author.session(), "PUBLIC");
        mvc.perform(t.write(put("/api/me/collections/selected/" + id), member.session(), null)).andExpect(status().isOk());
        long publicTotal = total(null);

        JsonNode draft = draft(author.session(), id);
        saveDraft(author.session(), id, draft, "RESTRICTED").andExpect(status().isOk());
        assertTrue(listIds(null).contains(id), "draft change does not affect the published version until publish");
        publish(author.session(), id);

        assertFalse(listIds(null).contains(id));
        assertEquals(publicTotal - 1, total(null), "total excludes restricted collections for guests");
        assertFalse(listIds(member.session()).contains(id));
        assertTrue(listIds(trusted.session()).contains(id));
        assertEquals(total(null) + countRestricted(), total(trusted.session()));

        mvc.perform(get("/api/collections/" + id + "/outline")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/collections/" + id + "/outline").session(member.session())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("当前角色无权阅读这个专题"));
        mvc.perform(get("/api/collections/" + id + "/outline").session(trusted.session())).andExpect(status().isOk());
        mvc.perform(get("/api/collections/" + id).session(member.session())).andExpect(status().isForbidden());
        mvc.perform(get("/api/collections/" + id + "/posts").session(member.session())).andExpect(status().isForbidden());

        long statsPublic = jdbc.queryForObject("select count(*) from community_collections c join collection_publications p on p.collection_id=c.id where c.status='PUBLISHED' and p.visibility='PUBLIC'", Long.class);
        mvc.perform(get("/api/stats").session(trusted.session())).andExpect(jsonPath("$.collectionCount").value(statsPublic));

        JsonNode selections = json.readTree(mvc.perform(get("/api/me/collections/selected").session(member.session())).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertFalse(selections.path("items").findValuesAsText("id").contains(String.valueOf(id)), "existing selection is hidden");
        mvc.perform(t.write(put("/api/me/collections/selected/" + id), t.withRole("late", "MEMBER").session(), null)).andExpect(status().isNotFound());
        t.setRole(member.id(), "TRUSTED");
        selections = json.readTree(mvc.perform(get("/api/me/collections/selected").session(member.session())).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(selections.path("items").findValuesAsText("id").contains(String.valueOf(id)), "selection reappears once permission is granted");
    }

    @Test void onlyAuthorsWithPermissionCanMakeACollectionRestricted() throws Exception {
        var member = t.withRole("plain-author", "MEMBER");
        long id = publishedCollection(member.session(), "PUBLIC");
        JsonNode draft = draft(member.session(), id);
        assertEquals("PUBLIC", draft.path("visibility").asText());
        assertEquals("PUBLIC", draft.path("publishedVisibility").asText());
        saveDraft(member.session(), id, draft, "RESTRICTED").andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("当前角色不能把专题设为受限"));
        saveDraft(member.session(), id, draft(member.session(), id), "SECRET").andExpect(status().isBadRequest());

        var author = t.withRole("demoted", "TRUSTED");
        long restricted = publishedCollection(author.session(), "RESTRICTED");
        assertEquals("RESTRICTED", draft(author.session(), restricted).path("publishedVisibility").asText());
        t.setRole(author.id(), "MEMBER");
        saveDraft(author.session(), restricted, draft(author.session(), restricted), "RESTRICTED").andExpect(status().isOk());
        publish(author.session(), restricted); // 保持受限可以发布
        saveDraft(author.session(), restricted, draft(author.session(), restricted), "PUBLIC").andExpect(status().isOk());
        saveDraft(author.session(), restricted, draft(author.session(), restricted), "RESTRICTED").andExpect(status().isForbidden());
    }

    @Test void duplicatesStartPublicAndInternalSavesKeepVisibility() throws Exception {
        var author = t.withRole("dup", "TRUSTED");
        long id = publishedCollection(author.session(), "RESTRICTED");
        String copy = mvc.perform(t.write(post("/api/me/collections/" + id + "/duplicate"), author.session(), Map.of("structureOnly", false)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals("PUBLIC", json.readTree(copy).path("visibility").asText());
        assertEquals("RESTRICTED", jdbc.queryForObject("select visibility from community_collections where id=?", String.class, id));
    }

    private long publishedCollection(MockHttpSession session, String visibility) throws Exception {
        String created = mvc.perform(t.write(post("/api/me/collections/drafts"), session, Map.of("name", "受限测试专题", "format", "CUSTOM")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = json.readTree(created).path("id").asLong();
        ObjectNode doc = (ObjectNode) json.readTree(created);
        doc.set("sections", json.valueToTree(List.of(Map.of("title", "第一章", "description", "", "entries",
                List.of(Map.of("kind", "NOTE", "title", "", "annotation", "", "body", "内部内容"))))));
        saveDraft(session, id, doc, visibility).andExpect(status().isOk());
        publish(session, id);
        return id;
    }

    private JsonNode draft(MockHttpSession session, long id) throws Exception {
        return json.readTree(mvc.perform(get("/api/me/collections/" + id + "/draft").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private org.springframework.test.web.servlet.ResultActions saveDraft(MockHttpSession session, long id, JsonNode draft, String visibility) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("revision", draft.path("revision").asLong());
        body.put("name", draft.path("name").asText());
        body.put("description", draft.path("description").asText(""));
        body.put("format", draft.path("format").asText());
        body.put("audience", draft.path("audience").asText(""));
        body.put("goal", draft.path("goal").asText(""));
        body.set("sections", draft.path("sections"));
        body.put("visibility", visibility);
        return mvc.perform(t.write(put("/api/me/collections/" + id + "/draft"), session, json.writeValueAsString(body)));
    }

    private void publish(MockHttpSession session, long id) throws Exception {
        long revision = draft(session, id).path("revision").asLong();
        mvc.perform(t.write(post("/api/me/collections/" + id + "/publish"), session, Map.of("revision", revision))).andExpect(status().isOk());
    }

    private List<Long> listIds(MockHttpSession session) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int page = 0; page < 100; page++) {
            JsonNode result = json.readTree(mvc.perform(t.read(get("/api/collections").param("page", "" + page).param("size", "50"), session))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            result.path("items").forEach(i -> ids.add(i.path("id").asLong()));
            if (result.path("items").size() < 50) break;
        }
        return ids;
    }

    private long total(MockHttpSession session) throws Exception {
        return json.readTree(mvc.perform(t.read(get("/api/collections"), session)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("total").asLong();
    }

    private long countRestricted() {
        return jdbc.queryForObject("select count(*) from community_collections c join collection_publications p on p.collection_id=c.id where c.status='PUBLISHED' and p.visibility='RESTRICTED'", Long.class);
    }
}
