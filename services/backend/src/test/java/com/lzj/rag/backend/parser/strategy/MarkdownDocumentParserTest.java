package com.lzj.rag.backend.parser.strategy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownDocumentParserTest {
    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void cellLineEndingsDoNotCreateTableRowsOrBreakEscaping(String newline) {
        String table = MarkdownDocumentParser.table(
                List.of("名称" + newline + "备注", "路径"),
                List.of(List.of("第一行" + newline + "第二行|值", "C:\\资料")));

        assertThat(table).isEqualTo("| 名称<br>备注 | 路径 |\n| --- | --- |\n"
                + "| 第一行<br>第二行\\|值 | C:\\\\资料 |");
        assertThat(table.lines()).hasSize(3);
    }
}
