package com.aitome.common;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/** 文本长度按 Unicode 码点计（FINNS-18 D3），与浏览器 [...s]、PostgreSQL varchar(n) 的字符计数一致；不使用按 UTF-16 单元计数的 @Size。 */
public final class TextLimits {
    private TextLimits() {}

    public static int length(String value) { return value == null ? 0 : value.codePointCount(0, value.length()); }

    /** 空白含全角空格等 Unicode 空白（String.strip），null 视为空。 */
    public static boolean blank(String value) { return value == null || value.strip().isEmpty(); }

    /** 按码点截断，不会把代理对截成半个字符。 */
    public static String cut(String value, int max) {
        if (value == null) return null;
        if (value.codePointCount(0, value.length()) <= max) return value;
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    /** 超过上限抛 400 TOO_LONG，并带 field/max/actual。 */
    public static void require(String field, String label, String value, int max) {
        int actual = length(value);
        if (actual <= max) return;
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("field", field); extra.put("max", max); extra.put("actual", actual);
        throw new ApiProblem(HttpStatus.BAD_REQUEST, label + "不能超过 " + max + " 个字符，当前 " + actual + "；输入已保留。", "TOO_LONG", extra);
    }
}
