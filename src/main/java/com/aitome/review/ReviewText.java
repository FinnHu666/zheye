package com.aitome.review;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

/** 文本规范化与截断工具。规范化规则见 REVIEW-P07：NFKC → 小写 → 去掉空白、零宽/控制字符、标点和符号。 */
final class ReviewText {
    private ReviewText() {}

    /** 规范化后的文本：chars 为保留下来的字符，gapBefore[i] 表示第 i 个字符前面原本有被去掉的字符（或位于文本开头）。 */
    record Normalized(char[] chars, boolean[] gapBefore) {
        int length() { return chars.length; }
        String text() { return new String(chars); }
    }

    static Normalized scan(String value) {
        if (value == null || value.isEmpty()) return new Normalized(new char[0], new boolean[0]);
        String folded = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        char[] out = new char[folded.length()];
        boolean[] gap = new boolean[folded.length()];
        int n = 0;
        boolean pendingGap = true; // 文本开头视为分隔
        for (int i = 0; i < folded.length(); ) {
            int cp = folded.codePointAt(i);
            int width = Character.charCount(cp);
            if (stripped(cp)) { pendingGap = true; i += width; continue; }
            for (int k = 0; k < width; k++) { out[n] = folded.charAt(i + k); gap[n] = k == 0 && pendingGap; n++; }
            pendingGap = false;
            i += width;
        }
        return new Normalized(Arrays.copyOf(out, n), Arrays.copyOf(gap, n));
    }

    static String normalize(String value) { return scan(value).text(); }

    /** 空白、零宽/格式/控制字符、各类标点与符号：规范化时去掉。 */
    static boolean stripped(int cp) {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return true;
        if ((cp >= 0xFE00 && cp <= 0xFE0F) || cp == 0x00AD) return true; // 变体选择符、软连字符
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.UNASSIGNED,
                 Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
                 Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }

    static String cut(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
