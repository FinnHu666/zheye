package com.aitome.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** FINNS-18 D3 / 复评#7：ReviewText.cut 按码点截断，不再截断代理对。 */
class ReviewTextCutTests {
    @Test void cutKeepsWholeEmoji() {
        assertEquals("😀😀", ReviewText.cut("😀😀😀", 2));
        assertEquals("abc", ReviewText.cut("abc", 120));
        assertNull(ReviewText.cut(null, 5));
        String boundary = "x".repeat(119) + "😀😀";
        String cut = ReviewText.cut(boundary, 120);
        assertEquals(120, cut.codePointCount(0, cut.length()));
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)), "不得以半个代理对结尾");
    }
}
