package com.aitome.review;

import com.aitome.access.AccessContext;
import com.aitome.common.ApiExceptionHandler.ApiProblem;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * 词库导入（需规 §10.8，REVIEW-D21/D23）：站长上传一个或多个 UTF-8 文本文件（文件名=标签、每行一词）或单个 CSV（词语,标签）。
 * 服务端不联网下载；预览不写库；提交时按标签批量落库，疑似误报（≤2 字符）默认不导入，重复词跳过，同词多类别保留最高等级。
 */
@Service
public class LexiconImportService {
    public static final int MAX_BYTES = 2 * 1024 * 1024, MAX_LINES = 20_000, MAX_WORD = 40, MAX_ITEMS_SHOWN = 2_000;
    private final JdbcTemplate jdbc;
    private final SensitiveRuleService rules;
    private final ReviewAudit audit;

    public LexiconImportService(JdbcTemplate jdbc, SensitiveRuleService rules, ReviewAudit audit) { this.jdbc = jdbc; this.rules = rules; this.audit = audit; }

    public record UploadedFile(String name, byte[] bytes) {}
    public record Group(String tag, int count, int suspect, int duplicate, String level, boolean enabled) {}
    public record Item(String phrase, String tag, boolean suspect, boolean duplicate, List<String> alsoIn) {}
    public record Preview(List<Group> groups, List<Item> items, int totalWords, int rejected, boolean truncated) {}
    public record GroupConfig(String tag, String level, Boolean enabled) {}
    public record Config(List<GroupConfig> groups, Boolean includeSuspect) {}

    /** 按词库类别给出的“默认建议”（可编辑预设，见 SENSITIVE_LEXICON_CANDIDATE.md）：政治/反动/涉枪涉爆为高并建议启用；其余建议先停用。 */
    static String suggestedLevel(String tag) {
        return switch (tag) {
            case "政治类型", "反动词库", "涉枪涉爆", "暴恐词库" -> "HIGH";
            case "广告类型" -> "LOW";
            default -> "MEDIUM";
        };
    }
    static boolean suggestedEnabled(String tag) { return List.of("政治类型", "反动词库", "涉枪涉爆").contains(tag); }

    private record Parsed(Map<String, Entry> byKey, int rejected) {}
    private static final class Entry {
        final String phrase, tag; final String level; final List<String> alsoIn = new ArrayList<>();
        Entry(String phrase, String tag, String level) { this.phrase = phrase; this.tag = tag; this.level = level; }
    }

    @Transactional(readOnly = true)
    public Preview preview(AccessContext operator, List<UploadedFile> files) {
        rules.requireOwner(operator, false);
        Parsed parsed = parse(files);
        Set<String> existing = existingKeys();
        Map<String, int[]> counts = new TreeMap<>();
        List<Item> items = new ArrayList<>();
        for (Map.Entry<String, Entry> e : parsed.byKey().entrySet()) {
            Entry entry = e.getValue();
            boolean suspect = suspect(entry.phrase), duplicate = existing.contains(e.getKey());
            int[] c = counts.computeIfAbsent(entry.tag, k -> new int[3]);
            c[0]++; if (suspect) c[1]++; if (duplicate) c[2]++;
            if (items.size() < MAX_ITEMS_SHOWN) items.add(new Item(entry.phrase, entry.tag, suspect, duplicate, entry.alsoIn));
        }
        List<Group> groups = new ArrayList<>();
        counts.forEach((tag, c) -> groups.add(new Group(tag, c[0], c[1], c[2], suggestedLevel(tag), suggestedEnabled(tag))));
        return new Preview(groups, items, parsed.byKey().size(), parsed.rejected(), parsed.byKey().size() > MAX_ITEMS_SHOWN);
    }

    @Transactional
    public Map<String, Object> commit(AccessContext operator, List<UploadedFile> files, Config config) {
        rules.requireOwner(operator, true);
        if (config == null || config.groups() == null || config.groups().isEmpty()) throw bad("请至少选择一个标签导入。");
        Parsed parsed = parse(files);
        Map<String, GroupConfig> chosen = new LinkedHashMap<>();
        for (GroupConfig g : config.groups()) {
            if (g.tag() == null || !SensitiveRuleService.LEVELS.contains(g.level() == null ? "MEDIUM" : g.level())) throw bad("标签或等级无效。");
            chosen.put(g.tag(), g);
        }
        boolean includeSuspect = Boolean.TRUE.equals(config.includeSuspect());
        Set<String> existing = existingKeys();
        List<Object[]> rows = new ArrayList<>();
        int skippedDuplicate = 0, skippedSuspect = 0, toEnable = 0;
        Timestamp now = Timestamp.from(Instant.now());
        for (Map.Entry<String, Entry> e : parsed.byKey().entrySet()) {
            Entry entry = e.getValue();
            GroupConfig group = chosen.get(entry.tag);
            if (group == null) continue;
            if (!includeSuspect && suspect(entry.phrase)) { skippedSuspect++; continue; }
            if (existing.contains(e.getKey())) { skippedDuplicate++; continue; }
            boolean enabled = Boolean.TRUE.equals(group.enabled());
            if (enabled) toEnable++;
            String note = "导入：" + entry.tag + (entry.alsoIn.isEmpty() ? "" : "（另见 " + String.join("、", entry.alsoIn) + "）");
            rows.add(new Object[]{entry.phrase, ReviewText.cut(note, 200), enabled, e.getKey(), group.level() == null ? "MEDIUM" : group.level(), entry.tag, operator.userId(), now, now});
        }
        if (toEnable > 0) rules.ensureCapacity(toEnable);
        jdbc.batchUpdate("insert into sensitive_rules(phrase,note,enabled,deleted,active_key,match_mode,level,tag,version,created_by,created_at,updated_at) values(?,?,?,false,?,'SUBSTRING',?,?,1,?,?,?)", rows);
        String summary = "导入 " + rows.size() + " 条（跳过重复 " + skippedDuplicate + "、疑似误报 " + skippedSuspect + "）";
        audit.write(ReviewAudit.Row.of("IMPORT_RULES", operator.userId(), operator.displayName()).rule(String.join("、", chosen.keySet()), toEnable > 0).note("", summary));
        return Map.of("added", rows.size(), "skippedDuplicate", skippedDuplicate, "skippedSuspect", skippedSuspect, "enabled", toEnable,
                "message", "已导入 " + rows.size() + " 条；跳过重复 " + skippedDuplicate + " 条、疑似误报 " + skippedSuspect + " 条。");
    }

    // ------------------------------------------------------------------ 解析

    private Parsed parse(List<UploadedFile> files) {
        if (files == null || files.isEmpty()) throw bad("请选择要导入的词库文件。");
        long bytes = 0;
        for (UploadedFile f : files) bytes += f.bytes().length;
        if (bytes > MAX_BYTES) throw bad("词库文件总大小不能超过 2MB。");
        Map<String, Entry> byKey = new LinkedHashMap<>();
        int lines = 0, rejected = 0;
        for (UploadedFile file : files) {
            String text = decode(file);
            boolean csv = file.name() != null && file.name().toLowerCase(Locale.ROOT).endsWith(".csv");
            String fileTag = tagOf(file.name());
            for (String raw : text.split("\\r?\\n")) {
                String line = raw.strip();
                if (line.isEmpty()) continue;
                if (++lines > MAX_LINES) throw bad("词库总行数不能超过 " + MAX_LINES + " 行。");
                String phrase = line, tag = fileTag;
                if (csv) {
                    int comma = Math.max(line.lastIndexOf(','), line.lastIndexOf('，'));
                    if (comma > 0) { phrase = line.substring(0, comma).strip(); String t = line.substring(comma + 1).strip(); if (!t.isEmpty()) tag = SensitiveRuleService.cleanTag(t); }
                }
                String key = ReviewText.normalize(phrase);
                if (key.isEmpty() || phrase.length() > MAX_WORD || tag == null) { rejected++; continue; }
                Entry previous = byKey.get(key);
                if (previous == null) byKey.put(key, new Entry(phrase, tag, suggestedLevel(tag)));
                else if (!previous.tag.equals(tag)) {
                    // 同词多类别：保留最高等级对应的类别，其余记为“另见”
                    if (SensitiveRuleService.rank(suggestedLevel(tag)) > SensitiveRuleService.rank(previous.level)) {
                        Entry replaced = new Entry(phrase, tag, suggestedLevel(tag)); replaced.alsoIn.addAll(previous.alsoIn); replaced.alsoIn.add(previous.tag); byKey.put(key, replaced);
                    } else if (!previous.alsoIn.contains(tag)) previous.alsoIn.add(tag);
                }
            }
        }
        return new Parsed(byKey, rejected);
    }

    private static String decode(UploadedFile file) {
        byte[] data = file.bytes();
        int offset = data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, data.length - offset)).toString();
        } catch (CharacterCodingException e) {
            throw bad("文件「" + file.name() + "」不是有效的 UTF-8 文本。");
        }
    }

    private static String tagOf(String fileName) {
        String name = fileName == null ? "" : fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return SensitiveRuleService.cleanTag(name);
    }

    /** 疑似误报：长度 ≤2 个字符。 */
    static boolean suspect(String phrase) { return phrase.strip().codePointCount(0, phrase.strip().length()) <= 2; }

    private Set<String> existingKeys() {
        return new HashSet<>(jdbc.queryForList("select active_key from sensitive_rules where deleted=false and active_key is not null", String.class));
    }

    private static ApiProblem bad(String message) { return new ApiProblem(HttpStatus.BAD_REQUEST, message); }
}
