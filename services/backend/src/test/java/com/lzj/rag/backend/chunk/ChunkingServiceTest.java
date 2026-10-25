package com.lzj.rag.backend.chunk;

import com.lzj.rag.backend.document.DocumentTypes.Splitter;
import com.lzj.rag.backend.ingestion.IngestionProperties;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.parser.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ChunkingServiceTest {
    final ChunkingService service=new ChunkingService(new IngestionProperties(Duration.ofSeconds(120),100000,1000,8192,16384,100));
    final MarkdownDocumentParser markdown=new MarkdownDocumentParser();
    @Test void retainsSectionsAndSourceOrderAndGeneratesStableTraceableIds(){
        String source="# 手册\n\n## 安装\n\nFirst paragraph.\n\nSecond paragraph.\n\n```java\nint n = 1;\n```\n\nAfter code.\n\n## 运维\n\nOther section.";
        var parsed=markdown.parse(source);UUID id=UUID.randomUUID();
        var chunks=service.chunk(id,1,parsed,new Splitter(null,null,null));
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).content()).contains("First paragraph.","Second paragraph.").doesNotContain("After code");
        assertThat(chunks.get(0).embeddingText()).startsWith("章节：手册 > 安装\n");
        assertThat(chunks.get(1).metadata().get("blockType")).isEqualTo("CODE");
        assertThat(chunks.get(3).embeddingText()).startsWith("章节：手册 > 运维\n");
        assertThat(service.chunk(id,1,parsed,new Splitter(null,null,null)).stream().map(Chunk::id).toList()).containsExactlyElementsOf(chunks.stream().map(Chunk::id).toList());
        assertThat(service.chunk(id,2,parsed,new Splitter(null,null,null)).get(0).id()).isNotEqualTo(chunks.get(0).id());
    }
    @Test void paragraphWindowsOverlapWithoutSplittingUnicodeSurrogates(){
        String text="😀".repeat(300);var parsed=new TextDocumentParser().parse(text);
        var chunks=service.chunk(UUID.randomUUID(),1,parsed,new Splitter(null,128,16));
        assertThat(chunks).hasSize(3).allSatisfy(c->{assertThat(c.charCount()).isLessThanOrEqualTo(128);assertThat(c.content().length()).isEqualTo(c.charCount()*2);});
        assertThat(chunks.get(1).content()).startsWith(chunks.get(0).content().substring(chunks.get(0).content().length()-32));
    }
    @Test void tableGroupsRepeatHeaderAndUseSemanticFieldsWithoutCuttingCells(){
        var parsed=markdown.parse("# 配置\n\n| 字段 | 值 |\n| --- | --- |\n| URL | localhost |\n| size | 10 |\n| mode | safe |\n");
        var chunks=service.chunk(UUID.randomUUID(),1,parsed,new Splitter(null,1024,128,2,512));
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).startsWith("| 字段 | 值 |").contains("| URL | localhost |");
        assertThat(chunks.get(0).embeddingText()).isEqualTo("章节：配置\n字段：URL；值：localhost\n字段：size；值：10");
        assertThat(chunks.get(1).metadata()).containsEntry("rowStart",3).containsEntry("rowEnd",3);
    }
    @Test void wholeDocumentAndOversizedStructuresHaveExplicitBudgets(){
        var parsed=markdown.parse("# Guide\n\n"+"paragraph ".repeat(200));
        assertThat(service.chunk(UUID.randomUUID(),1,parsed,new Splitter("WHOLE_DOCUMENT",1024,128))).hasSize(1);
        var big=markdown.parse("```text\n"+"x".repeat(8193)+"\n```\n");
        assertThatThrownBy(()->service.chunk(UUID.randomUUID(),1,big,new Splitter(null,null,null))).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_STRUCTURE_TOO_LARGE"));
        assertThatThrownBy(()->service.chunk(UUID.randomUUID(),1,markdown.parse("# Only heading"),new Splitter(null,null,null))).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_EMPTY_CONTENT"));
    }
    @Test void registryUsesProfileForPlainMimeAndRejectsConflictingClaims(){
        var registry=new ParserRegistry(List.of(new TextDocumentParser(),markdown));
        assertThat(registry.resolve("text/plain","AUTO","a.md")).isSameAs(markdown);
        assertThatThrownBy(()->new ParserRegistry(List.of(new TextDocumentParser(),new TextDocumentParser()))).isInstanceOf(IllegalStateException.class);
    }
}
