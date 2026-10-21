package com.aitome.review;

import com.aitome.access.AccessContext;
import com.aitome.access.AccessService;
import com.aitome.access.Permission;
import com.aitome.access.RequirePermission;
import com.aitome.review.LexiconImportService.*;
import com.aitome.review.SensitiveRuleService.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** FINNS-17 接口（契约 §4）。审批侧要求 content.review；规则侧在服务内校验“当前站长”；作者侧只读本人。 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {
    private final ReviewService review;
    private final SensitiveRuleService rules;
    private final AccessService access;
    private final LexiconImportService lexicon;
    private final ObjectMapper json;

    public ReviewController(ReviewService review, SensitiveRuleService rules, AccessService access, LexiconImportService lexicon, ObjectMapper json) { this.review = review; this.rules = rules; this.access = access; this.lexicon = lexicon; this.json = json; }

    public record DecisionRequest(String action, Long expectedGeneration, String note) {}
    public record ManageRequest(String action, String expectedModeration, String note, Boolean acknowledgeMatches) {}
    public record ToggleRequest(Boolean enabled, Long expectedVersion) {}
    public record DeleteRuleRequest(Long expectedVersion) {}
    public record BatchRequest(String tag, Boolean enabled) {}
    public record NotedRequest(String action) {}

    private long operator(HttpServletRequest http) { return access.current(http).userId(); }

    // ---- 作者侧 ----
    @GetMapping("/mine") @RequirePermission(Permission.PROFILE_MANAGE)
    public Map<String, Object> mine(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size, @RequestParam(required = false) String type, HttpServletRequest http) {
        return review.mine(operator(http), page, size, type);
    }

    @GetMapping("/mine/content") @RequirePermission(Permission.PROFILE_MANAGE)
    public Map<String, Object> mineByContent(@RequestParam String type, @RequestParam(defaultValue = "") String ids, HttpServletRequest http) {
        java.util.List<Long> parsed = new java.util.ArrayList<>();
        for (String part : ids.split(",")) { String t = part.trim(); if (t.matches("[0-9]{1,18}")) parsed.add(Long.parseLong(t)); }
        return review.flowsByContent(operator(http), type, parsed);
    }

    @GetMapping("/mine/unpublished") @RequirePermission(Permission.PROFILE_MANAGE)
    public Map<String, Object> mineUnpublished(@RequestParam String type, HttpServletRequest http) {
        return review.unpublishedFlows(operator(http), type);
    }

    @PostMapping("/mine/{taskId}/withdraw") @RequirePermission(Permission.PROFILE_MANAGE)
    public Map<String, Object> withdraw(@PathVariable long taskId, HttpServletRequest http) {
        AccessContext context = access.current(http);
        return review.withdrawTask(context.userId(), context.displayName(), taskId);
    }

    // ---- 审批侧 ----
    @GetMapping("/queue") @RequirePermission(Permission.CONTENT_REVIEW)
    public Map<String, Object> queue(@RequestParam(defaultValue = "PENDING") String status, @RequestParam(defaultValue = "") String type, @RequestParam(defaultValue = "") String level,
                                     @RequestParam(defaultValue = "0") int days, @RequestParam(defaultValue = "") String q,
                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size, HttpServletRequest http) {
        return review.queue(operator(http), status, type, level, days, q, page, size);
    }

    @GetMapping("/tasks/{id}") @RequirePermission(Permission.CONTENT_REVIEW)
    public Map<String, Object> task(@PathVariable long id, HttpServletRequest http) { return review.taskDetail(operator(http), id); }

    @PostMapping("/tasks/{id}") @RequirePermission(Permission.CONTENT_REVIEW)
    public Map<String, Object> decide(@PathVariable long id, @RequestBody DecisionRequest request, HttpServletRequest http) {
        return review.decide(operator(http), id, request.action(), request.expectedGeneration(), request.note());
    }

    @GetMapping("/publications") @RequirePermission(Permission.CONTENT_REVIEW)
    public Map<String, Object> publications(@RequestParam(defaultValue = "") String type, @RequestParam(defaultValue = "") String state, @RequestParam(defaultValue = "") String q,
                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size, HttpServletRequest http) {
        return review.publications(operator(http), type, state, q, page, size);
    }

    @GetMapping("/publications/{type}/{id}") @RequirePermission(Permission.CONTENT_REVIEW)
    public Map<String, Object> publication(@PathVariable String type, @PathVariable long id, HttpServletRequest http) { return review.publicationDetail(operator(http), type, id); }

    @PostMapping("/publications/{type}/{id}") @RequirePermission(Permission.CONTENT_REVIEW)
    public Map<String, Object> manage(@PathVariable String type, @PathVariable long id, @RequestBody ManageRequest request, HttpServletRequest http) {
        return review.manage(operator(http), type, id, request.action(), request.expectedModeration(), request.note(), Boolean.TRUE.equals(request.acknowledgeMatches()));
    }

    @GetMapping("/audit") @RequirePermission(Permission.CONTENT_REVIEW)
    public Map<String, Object> audit(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size, HttpServletRequest http) {
        return review.auditPage(operator(http), page, size);
    }

    // ---- 规则侧（当前站长） ----
    @GetMapping("/rules") @RequirePermission(Permission.PROFILE_MANAGE)
    public RulePage rules(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String level, @RequestParam(defaultValue = "") String tag,
                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "8") int size, HttpServletRequest http) {
        return rules.list(access.current(http), q, status, level, tag, page, size);
    }

    @GetMapping("/rules/history") @RequirePermission(Permission.PROFILE_MANAGE)
    public HistoryPage ruleHistory(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size, HttpServletRequest http) {
        return rules.history(access.current(http), page, size);
    }

    @PostMapping("/rules") @RequirePermission(Permission.PROFILE_MANAGE)
    public Rule createRule(@RequestBody RuleRequest request, HttpServletRequest http) { return rules.create(access.current(http), request); }

    @PutMapping("/rules/{id}") @RequirePermission(Permission.PROFILE_MANAGE)
    public Rule updateRule(@PathVariable long id, @RequestBody RuleRequest request, HttpServletRequest http) { return rules.update(access.current(http), id, request); }

    @PatchMapping("/rules/{id}/enabled") @RequirePermission(Permission.PROFILE_MANAGE)
    public Rule toggleRule(@PathVariable long id, @RequestBody ToggleRequest request, HttpServletRequest http) {
        return rules.toggle(access.current(http), id, Boolean.TRUE.equals(request.enabled()), request.expectedVersion());
    }

    @DeleteMapping("/rules/{id}") @RequirePermission(Permission.PROFILE_MANAGE)
    public Map<String, Object> deleteRule(@PathVariable long id, @RequestBody DeleteRuleRequest request, HttpServletRequest http) {
        rules.delete(access.current(http), id, request.expectedVersion());
        return Map.of("message", "规则已删除，不再参与后续检测；既有发布内容不变，操作记录保留。");
    }

    @PostMapping("/rules/batch") @RequirePermission(Permission.PROFILE_MANAGE)
    public Map<String, Object> batchRules(@RequestBody BatchRequest request, HttpServletRequest http) {
        return rules.batchToggle(access.current(http), request.tag(), Boolean.TRUE.equals(request.enabled()));
    }

    @PostMapping("/rules/import-preview") @RequirePermission(Permission.PROFILE_MANAGE)
    public Preview importPreview(@RequestParam("files") List<MultipartFile> files, HttpServletRequest http) throws IOException {
        return lexicon.preview(access.current(http), uploaded(files));
    }

    @PostMapping("/rules/import") @RequirePermission(Permission.PROFILE_MANAGE)
    public Map<String, Object> importRules(@RequestParam("files") List<MultipartFile> files, @RequestParam("config") String config, HttpServletRequest http) throws IOException {
        try {
            return lexicon.commit(access.current(http), uploaded(files), json.readValue(config, Config.class));
        } catch (JsonProcessingException e) {
            throw new com.aitome.common.ApiExceptionHandler.ApiProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "导入设置格式不正确。");
        }
    }

    private static List<UploadedFile> uploaded(List<MultipartFile> files) throws IOException {
        List<UploadedFile> result = new ArrayList<>();
        for (MultipartFile f : files) result.add(new UploadedFile(f.getOriginalFilename(), f.getBytes()));
        return result;
    }

    // ---- 低敏感留痕（审批侧） ----
    @GetMapping("/noted") @RequirePermission(Permission.CONTENT_REVIEW)
    public Map<String, Object> noted(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size, HttpServletRequest http) {
        return review.noted(operator(http), page, size);
    }

    @PostMapping("/noted/{id}") @RequirePermission(Permission.CONTENT_REVIEW)
    public Map<String, Object> markNoted(@PathVariable long id, @RequestBody NotedRequest request, HttpServletRequest http) {
        return review.markNoted(operator(http), id, "REVIEWED".equals(request.action()));
    }
}
