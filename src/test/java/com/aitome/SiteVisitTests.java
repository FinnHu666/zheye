package com.aitome;

import com.aitome.stats.SiteVisitService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** FINNS-10 验收 V01/V03：网站访问计数（契约 §2）。 */
@SpringBootTest @AutoConfigureMockMvc
class SiteVisitTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @SpyBean SiteVisitService visits;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test void firstEntryCountsAndIssuesSlidingCookieWithoutCsrfToken() throws Exception {
        long before = visits.currentTotal();
        MockHttpServletResponse response = enter(null, null);
        JsonNode body = json.readTree(response.getContentAsString());
        assertTrue(body.path("counted").asBoolean());
        assertEquals(before + 1, body.path("total").asLong());
        String setCookie = visitHeader(response);
        assertNotNull(setCookie);
        assertTrue(setCookie.startsWith("FINNS_VISIT="), setCookie);
        assertTrue(setCookie.contains("Max-Age=1800") && setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Lax"), setCookie);
    }

    @Test void refreshWithinWindowDoesNotCountButRenewsCookie() throws Exception {
        Cookie visit = visitCookie(enter(null, null));
        long total = visits.currentTotal();
        MockHttpServletResponse again = enter(visit, null);
        JsonNode body = json.readTree(again.getContentAsString());
        assertFalse(body.path("counted").asBoolean());
        assertEquals(total, body.path("total").asLong());
        assertEquals(total, visits.currentTotal());
        assertEquals(visit.getValue(), visitCookie(again).getValue(), "same visit keeps its marker");
    }

    @Test void expiredOrClearedCookieCountsAsNewVisit() throws Exception {
        enter(null, null);
        long total = visits.currentTotal();
        enter(null, null); // 浏览器在 30 分钟后不再发送已过期的 Cookie，或用户清除了 Cookie
        assertEquals(total + 1, visits.currentTotal());
    }

    @Test void inSiteApiRequestsRenewTheVisitCookie() throws Exception {
        Cookie visit = visitCookie(enter(null, null));
        String renewed = visitHeader(mvc.perform(get("/api/posts").cookie(visit)).andExpect(status().isOk()).andReturn().getResponse());
        assertNotNull(renewed);
        assertTrue(renewed.startsWith("FINNS_VISIT=" + visit.getValue()) && renewed.contains("Max-Age=1800"), renewed);
        assertNull(visitHeader(mvc.perform(get("/api/posts")).andReturn().getResponse()), "no visit cookie is invented for requests without a visit");
    }

    @Test void rejectsCrossSiteOriginAndNonJsonRequests() throws Exception {
        long total = visits.currentTotal();
        mvc.perform(post("/api/site-visits").contentType(MediaType.APPLICATION_JSON).content("{}").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/site-visits").contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        assertEquals(total, visits.currentTotal());
        mvc.perform(post("/api/site-visits").contentType(MediaType.APPLICATION_JSON).content("{}").header("Origin", "http://localhost"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.counted").value(true));
    }

    @Test void statsExposeVisitTotalAndStartingPoint() throws Exception {
        enter(null, null);
        mvc.perform(get("/api/stats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.visits.total").value(visits.currentTotal()))
                .andExpect(jsonPath("$.visits.since").isString())
                .andExpect(jsonPath("$.creatorCount").isNumber());
    }

    @Test void unreadableCounterLeavesOtherStatsIntactAndFailedCountIsReported() throws Exception {
        try {
            doReturn(Optional.empty()).when(visits).visits();
            mvc.perform(get("/api/stats")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.visits").doesNotExist())
                    .andExpect(jsonPath("$.contentCount").isNumber());
            doThrow(new IllegalStateException("db down")).when(visits).recordEntry();
            mvc.perform(post("/api/site-visits").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("访问计数暂不可用"));
        } finally { reset(visits); }
    }

    @Test void firstEntryRecreatesAMissingCounterRow() throws Exception {
        // 新的 H2 开发库在启动初始化完成前就可能收到首个请求
        jdbc.update("delete from site_visit_counter where id=1");
        JsonNode body = json.readTree(enter(null, null).getContentAsString());
        assertTrue(body.path("counted").asBoolean());
        assertEquals(1, body.path("total").asLong());
    }

    @Test void concurrentFirstEntriesAreAllCounted() throws Exception {
        long before = visits.currentTotal();
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/site-visits").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
            }));
            start.countDown();
            for (Future<Integer> result : results) assertEquals(200, result.get(30, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(before + threads, visits.currentTotal());
    }

    private MockHttpServletResponse enter(Cookie visit, String origin) throws Exception {
        var request = post("/api/site-visits").contentType(MediaType.APPLICATION_JSON).content("{}");
        if (visit != null) request.cookie(visit);
        if (origin != null) request.header("Origin", origin);
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
    }

    private String visitHeader(MockHttpServletResponse response) {
        return response.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("FINNS_VISIT=")).findFirst().orElse(null);
    }

    private Cookie visitCookie(MockHttpServletResponse response) {
        String header = visitHeader(response);
        assertNotNull(header, "visit cookie expected");
        String value = header.substring("FINNS_VISIT=".length(), header.indexOf(';'));
        return new Cookie("FINNS_VISIT", value);
    }
}
