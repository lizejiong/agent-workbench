package com.lzj.rag.ai.util;

import com.lzj.rag.ai.api.ApiTypes.TokenEstimate;
import com.lzj.rag.ai.service.AiException;
import java.util.regex.Pattern;

public final class TextUtilities {
    private static final Pattern FENCE = Pattern.compile("\\A(`{3,}|~{3,})[^\\r\\n`~]*\\R([\\s\\S]*)\\R\\1[\\t ]*\\z");
    private TextUtilities() { }
    public static String stripFence(String text) {
        if (text == null) throw AiException.input("text is required");
        String trimmed = text.trim();
        var match = FENCE.matcher(trimmed);
        if (!match.matches()) return text;
        String inner = match.group(2);
        // A closing fence followed by more prose or blocks is not one surrounding block.
        if (Pattern.compile("(?m)^" + Pattern.quote(match.group(1)) + "[\\t ]*$").matcher(inner).find()) return text;
        return inner;
    }
    public static TokenEstimate estimate(String text) {
        if (text == null) throw AiException.input("text is required");
        long wide = text.codePoints().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count();
        long other = text.codePointCount(0, text.length()) - wide;
        return new TokenEstimate(wide + (other + 3) / 4, true);
    }
}
