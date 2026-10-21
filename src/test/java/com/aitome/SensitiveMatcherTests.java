package com.aitome;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RV-A16：规范化与多模式匹配（REVIEW-P07）。直接测试 review 包内的匹配器，并与“逐条子串匹配”的朴素实现对拍。 */
class SensitiveMatcherTests {
    private static final String PACKAGE = "com.aitome.review.";

    // 反射访问包内可见类，避免为测试放宽可见性。
    private static Object rule(String phrase, boolean wholeWord) throws Exception {
        Class<?> normalizer = Class.forName(PACKAGE + "ReviewText");
        Method normalize = normalizer.getDeclaredMethod("normalize", String.class);
        normalize.setAccessible(true);
        Class<?> ruleClass = Class.forName(PACKAGE + "SensitiveMatcher$Rule");
        Constructor<?> constructor = ruleClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(phrase, normalize.invoke(null, phrase), wholeWord, "MEDIUM");
    }

    @SuppressWarnings("unchecked")
    private static List<String> match(List<Object> rules, String... texts) throws Exception {
        Class<?> matcherClass = Class.forName(PACKAGE + "SensitiveMatcher");
        Constructor<?> constructor = matcherClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object matcher = constructor.newInstance(rules);
        Method match = matcherClass.getDeclaredMethod("match", Iterable.class);
        match.setAccessible(true);
        return (List<String>) match.invoke(matcher, List.of(texts));
    }

    private static List<String> hits(String text, String... phrases) throws Exception {
        List<Object> rules = new ArrayList<>();
        for (String p : phrases) rules.add(rule(p, false));
        return match(rules, text);
    }

    @Test void caseWidthWhitespaceAndPunctuationVariantsAllMatch() throws Exception {
        for (String text : List.of("BadWord", "ＢＡＤＷＯＲＤ", "b a d w o r d", "b.a.d.w.o.r.d", "bad_word", "b​a‌d‍w⁠ord", "b-a-d,w!o?r…d", "BAD\nWORD", "b️adword"))
            assertEquals(List.of("badword"), hits("前缀 " + text + " 后缀", "badword"), text);
        assertEquals(List.of(), hits("bad world", "badword"));
    }

    @Test void chineseVariantsWithPunctuationMatch() throws Exception {
        assertEquals(List.of("演示拦截词"), hits("这里有「演 示，拦·截 词」。", "演示拦截词"));
        assertEquals(List.of(), hits("演示拦词", "演示拦截词"));
    }

    @Test void overlappingContainedAndRepeatedHitsAreAllReportedOnceInRuleOrder() throws Exception {
        assertEquals(List.of("he", "she", "hers"), hits("ushers she hers", "he", "she", "hers", "his"));
        assertEquals(List.of("a", "aa", "aaa"), hits("aaaa", "a", "aa", "aaa"));
        assertEquals(List.of("x"), hits("x x x x", "x"));
    }

    @Test void eachTextIsScannedSeparatelySoMatchesNeverSpanTwoFields() throws Exception {
        assertEquals(List.of(), match(List.of(rule("badword", false)), "标题 bad", "word 正文"));
        assertEquals(List.of("badword"), match(List.of(rule("badword", false)), "标题", "正文 badword"));
    }

    @Test void wholeWordOnlyConstrainsLatinEndsAndUsesOriginalSeparators() throws Exception {
        List<Object> whole = List.of(rule("cat", true));
        assertEquals(List.of("cat"), match(whole, "the cat sat"));
        assertEquals(List.of("cat"), match(whole, "cat"));
        assertEquals(List.of("cat"), match(whole, "(cat)"));
        assertEquals(List.of(), match(whole, "category"));
        assertEquals(List.of(), match(whole, "concat"));
        assertEquals(List.of("cat"), match(whole, "a cat, category"), "后面的独立出现仍应命中");
        // 中文端不受约束：整词模式下中文规则等同子串
        assertEquals(List.of("习"), match(List.of(rule("习", true)), "学习"));
        // 一端是中文、一端是英文：只约束英文端
        assertEquals(List.of("联系 qq"), match(List.of(rule("联系 qq", true)), "请联系QQ。"));
        assertEquals(List.of(), match(List.of(rule("联系 qq", true)), "请联系QQ号"));
    }

    @Test void substringModeKeepsShortWordFalsePositives() throws Exception {
        assertEquals(List.of("习"), hits("学习", "习")); // 已知局限：见示例词库
        assertEquals(List.of("cat"), hits("category", "cat"));
    }

    @Test void punctuationOnlyPhraseNormalizesToNothing() throws Exception {
        Object rule = rule("…… ！！", false);
        Method key = rule.getClass().getDeclaredMethod("key");
        key.setAccessible(true);
        assertEquals("", key.invoke(rule));
        assertEquals(List.of(), match(List.of(rule), "任何内容……！"));
    }

    @Test void automatonAgreesWithNaiveSubstringMatchingOnRandomInput() throws Exception {
        Random random = new Random(20261020);
        String alphabet = "abc 习学-. A";
        for (int round = 0; round < 400; round++) {
            List<String> phrases = new ArrayList<>();
            for (int i = 0; i < 1 + random.nextInt(6); i++) phrases.add(random(random, alphabet, 1 + random.nextInt(4)));
            String text = random(random, alphabet, random.nextInt(30));
            List<Object> rules = new ArrayList<>();
            for (String p : phrases) rules.add(rule(p, false));
            Method normalize = Class.forName(PACKAGE + "ReviewText").getDeclaredMethod("normalize", String.class);
            normalize.setAccessible(true);
            String haystack = (String) normalize.invoke(null, text);
            Set<String> expected = new LinkedHashSet<>();
            for (String p : phrases) {
                String key = (String) normalize.invoke(null, p);
                if (!key.isEmpty() && haystack.contains(key)) expected.add(p);
            }
            assertEquals(new ArrayList<>(expected), match(rules, text), "phrases=" + phrases + " text=[" + text + "]");
        }
    }

    @Test void largeRuleSetStaysFast() throws Exception {
        List<Object> rules = new ArrayList<>();
        for (int i = 0; i < 500; i++) rules.add(rule("规则词" + i + "号", false));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20000; i++) text.append("普通正文内容");
        text.append("规则词499号");
        long start = System.nanoTime();
        List<String> found = match(rules, text.toString());
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(List.of("规则词499号"), found);
        assertTrue(millis < 2000, "500 条规则 + 12 万字应在 2 秒内完成，实际 " + millis + "ms");
    }

    private static String random(Random random, String alphabet, int length) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return out.toString();
    }
}
