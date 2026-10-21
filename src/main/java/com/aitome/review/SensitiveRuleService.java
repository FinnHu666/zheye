package com.aitome.review;

import com.aitome.access.AccessContext;
import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.aitome.review.SensitiveMatcher.Hit;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 站内敏感词规则：检测（任何登录发布路径）与维护（仅当前站长）。需规修订6：三级、标签、批量启停。 */
@Service
public class SensitiveRuleService {
    public static final int MAX_ENABLED = 20_000;
    public static final List<String> LEVELS = List.of("LOW", "MEDIUM", "HIGH");
    private final JdbcTemplate jdbc;
    private final ReviewAudit audit;
    private volatile Cached cache;

    public SensitiveRuleService(JdbcTemplate jdbc, ReviewAudit audit) { this.jdbc = jdbc; this.audit = audit; }

    public record Rule(long id, String phrase, String note, boolean enabled, String level, String tag, String matchMode, long version, Instant updatedAt) {}
    public record TagCount(String tag, long total, long enabled) {}
    public record RulePage(List<Rule> items, long total, int page, int size, long enabledCount, long disabledCount, Map<String, Long> levels, List<TagCount> tags) {}
    public record RuleRequest(String phrase, String note, Boolean enabled, String matchMode, String level, String tag, Long expectedVersion) {}
    public record HistoryItem(long id, String action, String operator, String phrase, Boolean enabled, Instant at) {}
    public record HistoryPage(List<HistoryItem> items, long total, int page, int size) {}
    /** level 为 NONE/LOW/MEDIUM/HIGH（命中取最高等级）。 */
    public record Detection(List<Hit> hits, String level) {
        public List<String> phrases() { return hits.stream().map(Hit::phrase).distinct().toList(); }
    }

    private record Cached(String fingerprint, SensitiveMatcher matcher) {}

    // ------------------------------------------------------------------ 检测

    /** 检测不可用（启用规则为 0 或读取异常）→ 503，调用方不得发布。 */
    public Detection detect(Collection<String> texts) {
        SensitiveMatcher matcher = matcher();
        List<Hit> hits = matcher.hits(texts);
        String level = "NONE";
        for (Hit hit : hits) if (rank(hit.level()) > rank(level)) level = hit.level();
        return new Detection(hits, level);
    }

    static int rank(String level) { return switch (level) { case "LOW" -> 1; case "MEDIUM" -> 2; case "HIGH" -> 3; default -> 0; }; }

    /** 编译后的自动机按“启用规则指纹”缓存；指纹变化（新增/编辑/启停/删除）才重建。 */
    private SensitiveMatcher matcher() {
        try {
            Map<String, Object> row = jdbc.queryForMap("select count(*) as c, coalesce(sum(id),0) as i, coalesce(sum(version),0) as v, max(updated_at) as u from sensitive_rules where enabled=true and deleted=false");
            Map<String, Object> lower = new LinkedHashMap<>();
            row.forEach((k, v) -> lower.put(k.toLowerCase(), v));
            long count = ((Number) lower.get("c")).longValue();
            if (count == 0) throw unavailable();
            String fingerprint = count + ":" + lower.get("i") + ":" + lower.get("v") + ":" + lower.get("u");
            Cached cached = cache;
            if (cached != null && cached.fingerprint().equals(fingerprint)) return cached.matcher();
            List<SensitiveMatcher.Rule> rules = jdbc.query("select phrase,match_mode,level from sensitive_rules where enabled=true and deleted=false order by id",
                    (rs, n) -> new SensitiveMatcher.Rule(rs.getString(1), ReviewText.normalize(rs.getString(1)), "WHOLE_WORD".equals(rs.getString(2)), rs.getString(3)))
                    .stream().filter(r -> !r.key().isEmpty()).toList();
            if (rules.isEmpty()) throw unavailable();
            SensitiveMatcher built = new SensitiveMatcher(rules);
            cache = new Cached(fingerprint, built);
            return built;
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    private ApiProblem unavailable() {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "敏感检测暂不可用，本次未发布。草稿和旧发布版已保留。", "DETECTION_UNAVAILABLE", null);
    }

    // ------------------------------------------------------------------ 查询

    @Transactional(readOnly = true)
    public RulePage list(AccessContext operator, String q, String status, String level, String tag, int page, int size) {
        requireOwner(operator, false);
        int safePage = Math.max(0, Math.min(page, 100_000)), safeSize = Math.max(1, Math.min(size, 50));
        StringBuilder where = new StringBuilder(" where deleted=false");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String like = "%" + ReviewText.escapeLike(q.trim().toLowerCase()) + "%";
            where.append(" and (lower(phrase) like ? escape '\\' or lower(note) like ? escape '\\')");
            args.add(like); args.add(like);
        }
        if ("ENABLED".equals(status)) where.append(" and enabled=true");
        else if ("DISABLED".equals(status)) where.append(" and enabled=false");
        if (level != null && LEVELS.contains(level)) { where.append(" and level=?"); args.add(level); }
        if (tag != null && !tag.isBlank()) { where.append(" and tag=?"); args.add(tag); }
        Long total = jdbc.queryForObject("select count(*) from sensitive_rules" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize); pageArgs.add((long) safePage * safeSize);
        List<Rule> items = jdbc.query("select id,phrase,note,enabled,level,tag,match_mode,version,updated_at from sensitive_rules" + where + " order by updated_at desc,id desc limit ? offset ?", ruleMapper(), pageArgs.toArray());
        Long enabled = jdbc.queryForObject("select count(*) from sensitive_rules where deleted=false and enabled=true", Long.class);
        Long disabled = jdbc.queryForObject("select count(*) from sensitive_rules where deleted=false and enabled=false", Long.class);
        Map<String, Long> levels = new LinkedHashMap<>();
        for (String l : List.of("HIGH", "MEDIUM", "LOW")) levels.put(l, jdbc.queryForObject("select count(*) from sensitive_rules where deleted=false and enabled=true and level=?", Long.class, l));
        List<TagCount> tags = jdbc.query("select tag,count(*),sum(case when enabled then 1 else 0 end) from sensitive_rules where deleted=false and tag is not null group by tag order by tag",
                (rs, n) -> new TagCount(rs.getString(1), rs.getLong(2), rs.getLong(3)));
        return new RulePage(items, total == null ? 0 : total, safePage, safeSize, enabled == null ? 0 : enabled, disabled == null ? 0 : disabled, levels, tags);
    }

    @Transactional(readOnly = true)
    public HistoryPage history(AccessContext operator, int page, int size) {
        requireOwner(operator, false);
        int safePage = Math.max(0, Math.min(page, 100_000)), safeSize = Math.max(1, Math.min(size, 50));
        String ruleTypes = "type in ('RULE_CREATE','RULE_UPDATE','RULE_DELETE','BATCH_RULES','IMPORT_RULES')";
        Long total = jdbc.queryForObject("select count(*) from review_audit_log where " + ruleTypes, Long.class);
        List<HistoryItem> items = jdbc.query("select id,type,operator_name,phrase,rule_enabled,created_at from review_audit_log where " + ruleTypes + " order by created_at desc,id desc limit ? offset ?",
                (rs, n) -> new HistoryItem(rs.getLong(1), switch (rs.getString(2)) { case "RULE_CREATE" -> "新增规则"; case "RULE_DELETE" -> "删除规则"; case "BATCH_RULES" -> "按标签批量启停"; case "IMPORT_RULES" -> "导入词库"; default -> "修改规则"; },
                        rs.getString(3), rs.getString(4), (Boolean) rs.getObject(5), rs.getTimestamp(6).toInstant()), safeSize, (long) safePage * safeSize);
        return new HistoryPage(items, total == null ? 0 : total, safePage, safeSize);
    }

    // ------------------------------------------------------------------ 维护

    @Transactional
    public Rule create(AccessContext operator, RuleRequest request) {
        requireOwner(operator, true);
        String phrase = cleanPhrase(request.phrase()), note = cleanNote(request.note()), mode = cleanMode(request.matchMode()), level = cleanLevel(request.level()), tag = cleanTag(request.tag());
        boolean enabled = request.enabled() == null || request.enabled();
        String key = ReviewText.normalize(phrase);
        if (key.isEmpty()) throw bad("规则只包含标点或空白，无法用于匹配，请输入有实际文字的词语。");
        ensureUnique(key, null);
        if (enabled) ensureCapacity(1);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into sensitive_rules(phrase,note,enabled,deleted,active_key,match_mode,level,tag,version,created_by,created_at,updated_at) values(?,?,?,false,?,?,?,?,1,?,?,?)",
                phrase, note, enabled, key, mode, level, tag, operator.userId(), now, now);
        Long id = jdbc.queryForObject("select id from sensitive_rules where active_key=?", Long.class, key);
        audit.write(ReviewAudit.Row.of("RULE_CREATE", operator.userId(), operator.displayName()).rule(phrase, enabled).note(note, "规则已新增（" + level + "）"));
        return get(id);
    }

    @Transactional
    public Rule update(AccessContext operator, long id, RuleRequest request) {
        requireOwner(operator, true);
        Rule before = require(id);
        String phrase = cleanPhrase(request.phrase()), note = cleanNote(request.note());
        boolean enabled = request.enabled() == null ? before.enabled() : request.enabled();
        String mode = request.matchMode() == null ? before.matchMode() : cleanMode(request.matchMode());
        String level = request.level() == null ? before.level() : cleanLevel(request.level());
        String tag = request.tag() == null ? before.tag() : cleanTag(request.tag());
        String key = ReviewText.normalize(phrase);
        if (key.isEmpty()) throw bad("规则只包含标点或空白，无法用于匹配，请输入有实际文字的词语。");
        ensureUnique(key, id);
        if (enabled && !before.enabled()) ensureCapacity(1);
        int updated = jdbc.update("update sensitive_rules set phrase=?,note=?,enabled=?,active_key=?,match_mode=?,level=?,tag=?,version=version+1,updated_at=? where id=? and version=? and deleted=false",
                phrase, note, enabled, key, mode, level, tag, Timestamp.from(Instant.now()), id, request.expectedVersion() == null ? -1 : request.expectedVersion());
        if (updated == 0) throw conflict("规则已在别处修改，请重新读取列表后再编辑。");
        audit.write(ReviewAudit.Row.of("RULE_UPDATE", operator.userId(), operator.displayName()).rule(phrase, enabled).note(note, "规则已修改（" + level + "）"));
        return get(id);
    }

    @Transactional
    public Rule toggle(AccessContext operator, long id, boolean enabled, Long expectedVersion) {
        requireOwner(operator, true);
        Rule before = require(id);
        if (enabled && !before.enabled()) ensureCapacity(1);
        int updated = jdbc.update("update sensitive_rules set enabled=?,version=version+1,updated_at=? where id=? and version=? and deleted=false",
                enabled, Timestamp.from(Instant.now()), id, expectedVersion == null ? -1 : expectedVersion);
        if (updated == 0) throw conflict("规则已在别处修改，请重新读取列表后再操作。");
        audit.write(ReviewAudit.Row.of("RULE_UPDATE", operator.userId(), operator.displayName()).rule(before.phrase(), enabled).note(before.note(), enabled ? "规则已启用" : "规则已停用"));
        return get(id);
    }

    /** 按标签整批启用/停用（需规 §10.8）：一个事务、逐行版本号 +1、写一条记录。 */
    @Transactional
    public Map<String, Object> batchToggle(AccessContext operator, String tag, boolean enabled) {
        requireOwner(operator, true);
        String clean = cleanTag(tag);
        if (clean == null) throw bad("请选择标签。");
        Integer total = jdbc.queryForObject("select count(*) from sensitive_rules where deleted=false and tag=?", Integer.class, clean);
        if (total == null || total == 0) throw new ApiProblem(HttpStatus.NOT_FOUND, "没有该标签的规则。");
        if (enabled) {
            Integer toEnable = jdbc.queryForObject("select count(*) from sensitive_rules where deleted=false and tag=? and enabled=false", Integer.class, clean);
            ensureCapacity(toEnable == null ? 0 : toEnable);
        }
        int count = jdbc.update("update sensitive_rules set enabled=?,version=version+1,updated_at=? where deleted=false and tag=? and enabled<>?", enabled, Timestamp.from(Instant.now()), clean, enabled);
        audit.write(ReviewAudit.Row.of("BATCH_RULES", operator.userId(), operator.displayName()).rule(clean, enabled).note("", "标签「" + clean + "」" + (enabled ? "启用" : "停用") + " " + count + " 条"));
        return Map.of("count", count, "message", "已" + (enabled ? "启用" : "停用") + "标签「" + clean + "」下 " + count + " 条规则，仅影响后续提交。");
    }

    @Transactional
    public void delete(AccessContext operator, long id, Long expectedVersion) {
        requireOwner(operator, true);
        Rule before = require(id);
        int updated = jdbc.update("update sensitive_rules set deleted=true,enabled=false,active_key=null,version=version+1,updated_at=? where id=? and version=? and deleted=false",
                Timestamp.from(Instant.now()), id, expectedVersion == null ? -1 : expectedVersion);
        if (updated == 0) throw conflict("规则已在别处修改，请核对后再删除。");
        audit.write(ReviewAudit.Row.of("RULE_DELETE", operator.userId(), operator.displayName()).rule(before.phrase(), false).note(before.note(), "规则已删除"));
    }

    // ------------------------------------------------------------------ helpers

    private static org.springframework.jdbc.core.RowMapper<Rule> ruleMapper() {
        return (rs, n) -> new Rule(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getTimestamp(9).toInstant());
    }

    private Rule get(long id) {
        return jdbc.queryForObject("select id,phrase,note,enabled,level,tag,match_mode,version,updated_at from sensitive_rules where id=?", ruleMapper(), id);
    }

    private Rule require(long id) {
        List<Rule> rows = jdbc.query("select id,phrase,note,enabled,level,tag,match_mode,version,updated_at from sensitive_rules where id=? and deleted=false", ruleMapper(), id);
        if (rows.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "规则已被删除，请重新读取列表。");
        return rows.get(0);
    }

    private void ensureUnique(String key, Long exceptId) {
        Integer clash = exceptId == null
                ? jdbc.queryForObject("select count(*) from sensitive_rules where active_key=?", Integer.class, key)
                : jdbc.queryForObject("select count(*) from sensitive_rules where active_key=? and id<>?", Integer.class, key, exceptId);
        if (clash != null && clash > 0) throw bad("此词或短语已存在，请直接编辑原规则。");
    }

    /** 新增启用的规则数不得使启用总数超过上限。 */
    void ensureCapacity(int additional) {
        Integer enabled = jdbc.queryForObject("select count(*) from sensitive_rules where enabled=true and deleted=false", Integer.class);
        if (enabled != null && enabled + additional > MAX_ENABLED) throw bad("启用规则最多 " + MAX_ENABLED + " 条（当前 " + enabled + " 条），请先停用或删除不用的规则。");
    }

    private static String cleanPhrase(String value) {
        String phrase = value == null ? "" : value.trim();
        if (phrase.isEmpty() || phrase.length() > 40) throw bad("请输入1–40字的词或短语。");
        return phrase;
    }

    private static String cleanMode(String value) {
        if (value == null || value.isBlank() || "SUBSTRING".equals(value)) return "SUBSTRING";
        if ("WHOLE_WORD".equals(value)) return "WHOLE_WORD";
        throw bad("匹配方式只能是子串或整词。");
    }

    private static String cleanLevel(String value) {
        if (value == null || value.isBlank()) return "MEDIUM";
        if (!LEVELS.contains(value)) throw bad("敏感等级只能是低、中、高。");
        return value;
    }

    static String cleanTag(String value) {
        if (value == null) return null;
        String tag = value.trim();
        if (tag.isEmpty()) return null;
        if (tag.length() > 30) throw bad("标签最多30字。");
        return tag;
    }

    private static String cleanNote(String value) {
        String note = value == null ? "" : value.trim();
        if (note.length() > 200) throw bad("规则备注最多200字。");
        return note;
    }

    private static ApiProblem bad(String message) { return new ApiProblem(HttpStatus.BAD_REQUEST, message); }
    private static ApiProblem conflict(String message) { return new ApiProblem(HttpStatus.CONFLICT, message, "STALE", null); }

    /** 仅当前站长（站长指针在事务内重读）。写操作先锁站长状态行，与转交串行。 */
    void requireOwner(AccessContext operator, boolean lock) {
        if (operator == null || operator.isGuest()) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "请先登录");
        Long owner = jdbc.queryForObject("select owner_user_id from access_control_state where id=1" + (lock ? " for update" : ""), Long.class);
        Integer active = jdbc.queryForObject("select count(*) from user_accounts where id=? and account_status='ACTIVE'", Integer.class, operator.userId());
        if (!Objects.equals(owner, operator.userId()) || active == null || active == 0)
            throw new ApiProblem(HttpStatus.FORBIDDEN, "只有当前站长可以维护敏感词与规则。");
    }
}
