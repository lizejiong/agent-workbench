package com.lzj.rag.ai.text.converter;

import com.lzj.rag.ai.text.controller.vo.TextVO;
import com.lzj.rag.ai.text.controller.vo.TokenEstimateVO;
import com.lzj.rag.ai.text.service.bo.TokenEstimateBO;

/** 文本业务结果到 HTTP 响应对象的纯转换。 */
public final class TextWebConverter {

    /** 工具类不需要实例，所有转换都通过静态方法完成。 */
    private TextWebConverter() {}

    /**
     * 把 Token 估算业务结果转换为响应对象。
     *
     * @param result 当前业务处理或对象转换得到的结果。
     * @return 保留数量和估算标识的响应对象。
     */
    public static TokenEstimateVO toVO(TokenEstimateBO result) {
        return new TokenEstimateVO(result.tokens(), result.estimated());
    }

    /**
     * 把处理后的文本包装为文本响应。
     *
     * @param text 文本工具输入或输出内容；空字符串有效，输入不接受 null。
     * @return 只包含处理后文本的响应对象。
     */
    public static TextVO toVO(String text) {
        return new TextVO(text);
    }
}
