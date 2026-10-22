package com.aitome;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.aitome.common.TextLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** FINNS-18 D3：字数按 Unicode 码点计（契约 §3.0、CT-A02）。 */
class TextLimitsTests {
    @Test void countsCodePointsNotUtf16Units() {
        assertEquals(0, TextLimits.length(null));
        assertEquals(1, TextLimits.length("a"));
        assertEquals(1, TextLimits.length("😀"), "emoji 是 2 个 UTF-16 单元，但算 1 个字符");
        assertEquals(2, "😀".length());
        assertEquals(1, TextLimits.length("𠮷"));
        assertEquals(5, TextLimits.length("👨‍👩‍👧"), "组合 emoji 按码点算，属已知口径（契约能力边界表）");
    }

    @Test void blankTreatsUnicodeWhitespaceAsEmpty() {
        assertTrue(TextLimits.blank(null)); assertTrue(TextLimits.blank("")); assertTrue(TextLimits.blank("  \n"));
        assertTrue(TextLimits.blank("　"), "全角空格也是空白");
        assertFalse(TextLimits.blank(" x "));
    }

    @Test void cutNeverSplitsSurrogatePairs() {
        assertEquals("😀😀", TextLimits.cut("😀😀😀", 2));
        assertEquals("ab", TextLimits.cut("abc", 2));
        assertEquals("abc", TextLimits.cut("abc", 5));
        assertNull(TextLimits.cut(null, 3));
        assertTrue(Character.isHighSurrogate("😀😀😀".charAt(0)));
        String cut = TextLimits.cut("😀😀😀", 1);
        assertEquals(2, cut.length()); assertEquals("😀", cut);
    }

    @Test void requireAcceptsExactLimitAndRejectsOneOver() {
        TextLimits.require("title", "标题", "😀".repeat(120), 120);
        ApiProblem problem = assertThrows(ApiProblem.class, () -> TextLimits.require("title", "标题", "😀".repeat(121), 120));
        assertEquals(400, problem.status()); assertEquals("TOO_LONG", problem.code());
        assertEquals("title", problem.extra().get("field")); assertEquals(120, problem.extra().get("max")); assertEquals(121, problem.extra().get("actual"));
        assertTrue(problem.getMessage().contains("标题"));
        assertEquals(20000, TextLimits.length("x".repeat(20000)));
        assertThrows(ApiProblem.class, () -> TextLimits.require("body", "正文", "x".repeat(20001), 20000));
    }
}
