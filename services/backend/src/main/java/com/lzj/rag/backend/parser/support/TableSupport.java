package com.lzj.rag.backend.parser.support;

import java.util.*;

/** 为缺失表头的列生成稳定名称，保持表格语义可读。 */
public final class TableSupport {

    /** 禁止实例化仅包含类型定义或静态方法的工具类。 */
    private TableSupport() {}

    /**
     * 规范化表头，对空列名生成稳定的列名称。
     *
     * @param cells 当前行收集的单元格文本。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static List<String> header(List<String> cells) {
        // seen：用于检测重复输入位置或标识的集合。
        var seen = new HashSet<String>();
        // out：当前结果集合或文件输出流。
        var out = new ArrayList<String>();
        // i：当前遍历位置，按原始输入顺序递增。
        for (int i = 0; i < cells.size(); i++) {
            // value：当前待校验、转换或写入的值。
            String value = cells.get(i).isBlank() ? "Column " + (i + 1) : cells.get(i);
            while (!seen.add(value)) value = value + " [column " + (i + 1) + "]";
            out.add(value);
        }
        return List.copyOf(out);
    }
}
