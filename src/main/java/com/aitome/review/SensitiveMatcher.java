package com.aitome.review;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多模式匹配（REVIEW-P07）：把全部启用规则建成 Aho-Corasick 自动机（带失败指针的 DFA），文本只扫描一遍即得到所有命中。
 * 项目内自行实现，不引入第三方依赖。结果与“逐条规则做子串匹配（再按整词约束过滤）”逐位一致，只在速度上不同。
 */
final class SensitiveMatcher {
    /** key 为已规范化的词语（见 {@link ReviewText#normalize}）。 */
    record Rule(String phrase, String key, boolean wholeWord, String level) {}
    record Hit(String phrase, String level) {}

    private final List<Rule> rules;
    private final List<Map<Character, Integer>> next = new ArrayList<>();
    private final List<Integer> fail = new ArrayList<>();
    private final List<List<Integer>> ends = new ArrayList<>(); // 以该节点结尾的规则编号（含沿失败链合并的）

    SensitiveMatcher(List<Rule> rules) {
        this.rules = List.copyOf(rules);
        newNode();
        for (int i = 0; i < this.rules.size(); i++) {
            String key = this.rules.get(i).key();
            if (key == null || key.isEmpty()) continue;
            int node = 0;
            for (int c = 0; c < key.length(); c++) {
                Integer child = next.get(node).get(key.charAt(c));
                if (child == null) { child = newNode(); next.get(node).put(key.charAt(c), child); }
                node = child;
            }
            ends.get(node).add(i);
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int child : next.get(0).values()) queue.add(child);
        while (!queue.isEmpty()) {
            int node = queue.poll();
            for (Map.Entry<Character, Integer> edge : next.get(node).entrySet()) {
                char c = edge.getKey();
                int child = edge.getValue();
                int f = fail.get(node);
                while (f != 0 && !next.get(f).containsKey(c)) f = fail.get(f);
                Integer target = next.get(f).get(c);
                int link = target != null && target != child ? target : 0;
                fail.set(child, link);
                ends.get(child).addAll(ends.get(link));
                queue.add(child);
            }
        }
    }

    private int newNode() {
        next.add(new HashMap<>());
        fail.add(0);
        ends.add(new ArrayList<>());
        return next.size() - 1;
    }

    /** 返回命中的规则词语（去重，保持规则顺序）。每段文本单独扫描，不会跨段拼出命中。 */
    List<String> match(Iterable<String> texts) {
        List<String> phrases = new ArrayList<>();
        for (Hit hit : hits(texts)) if (!phrases.contains(hit.phrase())) phrases.add(hit.phrase());
        return phrases;
    }

    /** 命中的规则（保持规则顺序），带等级；同一词语出现在多条规则时每条各算一次。 */
    List<Hit> hits(Iterable<String> texts) {
        Set<Integer> hit = new java.util.TreeSet<>();
        for (String text : texts) scan(ReviewText.scan(text), hit);
        List<Hit> result = new ArrayList<>();
        for (int index : hit) result.add(new Hit(rules.get(index).phrase(), rules.get(index).level()));
        return result;
    }

    private void scan(ReviewText.Normalized text, Set<Integer> hit) {
        int node = 0;
        char[] chars = text.chars();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            while (node != 0 && !next.get(node).containsKey(c)) node = fail.get(node);
            Integer step = next.get(node).get(c);
            node = step == null ? 0 : step;
            for (int index : ends.get(node)) {
                if (hit.contains(index)) continue;
                Rule rule = rules.get(index);
                if (!rule.wholeWord() || boundaryOk(text, i + 1 - rule.key().length(), i + 1)) hit.add(index);
            }
        }
    }

    /** 整词只约束以英文字母/数字开头或结尾的一端：该端相邻处必须是原文分隔或文本边界；中文端不受约束。 */
    static boolean boundaryOk(ReviewText.Normalized text, int start, int end) {
        char[] chars = text.chars();
        if (asciiAlnum(chars[start]) && !text.gapBefore()[start]) return false;
        if (asciiAlnum(chars[end - 1]) && end < chars.length && !text.gapBefore()[end]) return false;
        return true;
    }

    private static boolean asciiAlnum(char c) { return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'); }
}
