package com.aitome;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class CollectionFeatureGateTests {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    @Test void defaultClosedFlagsRejectSchedulingAndNonListLayouts() throws Exception {
        var session=new MockHttpSession();TestUsers.registerAndLogin(mvc,jdbc,session,UUID.randomUUID()+"@finns.dev","password123","开关测试").andExpect(status().isOk());
        Cookie csrf=mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        var result=mvc.perform(post("/api/me/collections/drafts").session(session).cookie(csrf).header("X-XSRF-TOKEN",csrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name","默认关闭","format","CUSTOM","templateKey","BLANK","initialContent","内容")))).andExpect(status().isCreated()).andReturn();
        var draft=json.readTree(result.getResponse().getContentAsString());long id=draft.get("id").asLong();
        mvc.perform(get("/api/me/collections/capabilities").session(session)).andExpect(jsonPath("$.schedulingEnabled").value(false)).andExpect(jsonPath("$.layoutsEnabled").value(false));
        mvc.perform(post("/api/me/collections/"+id+"/publish").session(session).cookie(csrf).header("X-XSRF-TOKEN",csrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("revision",draft.get("revision").asLong(),"scheduledAt",Instant.now().plusSeconds(600).toString())))).andExpect(status().isBadRequest());
        ((com.fasterxml.jackson.databind.node.ObjectNode)draft).set("metadata",json.valueToTree(Map.of("sectionLayouts",List.of("CARDS"))));
        mvc.perform(put("/api/me/collections/"+id+"/draft").session(session).cookie(csrf).header("X-XSRF-TOKEN",csrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(draft))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/collections/"+id)).andExpect(status().isNotFound());
    }
}
