package com.aitome.content;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import java.text.Normalizer;
import java.util.*;

/** Creative fields belong to the draft and are copied into immutable publication snapshots. */
public record CollectionMetadata(List<String> tags, String category, String seoTitle, List<String> audiences,
                                 String coverId, List<String> sectionLayouts) {
    public static final List<String> CATEGORIES = List.of("ai", "engineering", "operations", "productivity", "learning", "career", "life", "other");
    public static final List<String> AUDIENCES = List.of("学生", "职场人士", "产品/运营", "开发者", "设计师", "创业者", "AI 爱好者", "其他");
    private static final ObjectMapper JSON = new ObjectMapper();
    public static CollectionMetadata empty() { return new CollectionMetadata(List.of(), "", "", List.of(), null, List.of()); }
    public CollectionMetadata normalized() {
        List<String> out = new ArrayList<>(); Set<String> seen = new HashSet<>();
        if (tags != null) for (String value : tags) {
            if (value == null) throw bad("标签不能为空");
            String tag = Normalizer.normalize(value, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
            if (tag.isEmpty() || tag.codePointCount(0, tag.length()) > 12) throw bad("标签需要1–12个字符");
            if (seen.add(tag.toLowerCase(Locale.ROOT))) out.add(tag);
        }
        if (out.size() > 6) throw bad("最多6个标签");
        String cat = category == null ? "" : category;
        if (!cat.isEmpty() && !CATEGORIES.contains(cat)) throw bad("分类不正确");
        String seo = seoTitle == null ? "" : seoTitle.strip();
        if (seo.length() > 60) throw bad("SEO标题最多60个字符");
        List<String> people = audiences == null ? List.of() : audiences.stream().distinct().toList();
        if (people.stream().anyMatch(Objects::isNull) || !AUDIENCES.containsAll(people)) throw bad("适用人群不正确");
        List<String> layouts = sectionLayouts == null ? List.of() : sectionLayouts;
        if (layouts.size() > 50 || layouts.stream().anyMatch(x -> x==null || !List.of("LIST", "CARDS", "TIMELINE").contains(x))) throw bad("章节展示样式不正确");
        if (coverId != null && !coverId.matches("[0-9a-f-]{36}")) throw bad("封面标识不正确");
        return new CollectionMetadata(List.copyOf(out), cat, seo, people, coverId, List.copyOf(layouts));
    }
    public static CollectionMetadata parse(String value) {
        if (value == null || value.isBlank()) return empty();
        try { return JSON.readValue(value, CollectionMetadata.class); } catch (Exception e) { throw new IllegalStateException("Invalid stored collection metadata", e); }
    }
    public String toJson() { try { return JSON.writeValueAsString(this); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static ApiProblem bad(String message) { return new ApiProblem(HttpStatus.BAD_REQUEST, message); }
}
