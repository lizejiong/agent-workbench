package com.lzj.rag.ai.text.service.impl;

import com.lzj.rag.ai.text.service.TextService;
import com.lzj.rag.ai.text.service.bo.TokenEstimateBO;
import com.lzj.rag.framework.convention.exception.ClientException;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/** 文本工具实现，提供粗略 Token 估算和完整代码围栏移除。 */
@Service
public class TextServiceImpl implements TextService {

    /** 只匹配覆盖整个文本、首尾同类同长围栏的正则表达式。 */
    private static final Pattern FENCE =
            Pattern.compile("\\A(`{3,}|~{3,})[^\\r\\n`~]*\\R([\\s\\S]*)\\R\\1[\\t ]*\\z");

    /**
     * 只移除覆盖整个 trimmed 文本的一层完整代码围栏；混合正文和多个代码块保持原文。
     *
     * @param text 文本工具输入或输出内容；空字符串有效，输入不接受 null。
     * @return 围栏内部内容，或不满足完整包围条件时的原文。
     */
    @Override
    public String stripCodeFence(String text) {
        if (text == null) throw new ClientException("text is required");
        // 仅用于判断外围代码围栏的去首尾空白副本，不直接替换不匹配的原文。
        String trimmed = text.trim();
        // 整段文本对围栏格式的匹配结果。
        var match = FENCE.matcher(trimmed);
        if (!match.matches()) return text;
        // 最外层围栏包围的内容，还需检查是否夹有额外闭合围栏。
        String inner = match.group(2);
        // 内部若还有完整闭合围栏，说明存在多段代码或混合正文，应保留原文。
        if (Pattern.compile("(?m)^" + Pattern.quote(match.group(1)) + "[\\t ]*$")
                .matcher(inner)
                .find()) return text;
        return inner;
    }

    /**
     * 按汉字与其他 Unicode 码点的粗略比例估算 Token 数，不用于计费或强制截断。
     *
     * @param text 文本工具输入或输出内容；空字符串有效，输入不接受 null。
     * @return 包含估算数量和 estimated=true 的业务结果。
     */
    @Override
    public TokenEstimateBO estimateTokens(String text) {
        if (text == null) throw new ClientException("text is required");
        // c：当前 Unicode 码点，用于判断是否属于汉字脚本。
        // 文本中 Unicode 脚本属于汉字的码点数量。
        long wide =
                text.codePoints()
                        .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                        .count();
        // 除汉字外的其他 Unicode 码点数量。
        long other = text.codePointCount(0, text.length()) - wide;
        return new TokenEstimateBO(wide + (other + 3) / 4, true);
    }
}
