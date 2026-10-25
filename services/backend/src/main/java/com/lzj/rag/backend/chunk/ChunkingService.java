package com.lzj.rag.backend.chunk;

import com.lzj.rag.backend.document.DocumentTypes.Splitter;
import com.lzj.rag.backend.ingestion.IngestionProperties;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.model.EmbeddingCatalog;
import com.lzj.rag.backend.parser.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.lzj.rag.backend.parser.ParsedDocument.*;

@Service
@EnableConfigurationProperties(IngestionProperties.class)
public class ChunkingService {
    private final IngestionProperties limits;
    public ChunkingService(IngestionProperties limits) { this.limits=limits; }
    public List<Chunk> chunk(UUID documentId,long generation,ParsedDocument parsed,Splitter spec) {
        if(spec.maxChars()>limits.hardChunkChars()) throw invalid("Chunk target exceeds configured hard budget");
        if(parsed.blocks().stream().noneMatch(b->b.blockType()!=BlockType.HEADING)) throw empty();
        var output=new ArrayList<Chunk>();
        if(spec.kind().equals("WHOLE_DOCUMENT")) {
            emit(output,documentId,generation,parsed.text().strip(),parsed.text().strip(),parsed.blocks(),Map.of("wholeDocument",true),parsed.parserVersion());
            return List.copyOf(output);
        }
        var blocks=parsed.blocks();
        for(int i=0;i<blocks.size();) {
            Block block=blocks.get(i);
            if(block.blockType()==BlockType.HEADING) { i++;continue; }
            if(block.blockType()==BlockType.PARAGRAPH) {
                int end=i+1;
                while(end<blocks.size() && blocks.get(end).blockType()==BlockType.PARAGRAPH
                        && blocks.get(end).index()==blocks.get(end-1).index()+1 && blocks.get(end).chapterPath().equals(block.chapterPath())
                        && Objects.equals(blocks.get(end).source().get("pageNumber"),block.source().get("pageNumber"))
                        && Objects.equals(blocks.get(end).source().get("slideNumber"),block.source().get("slideNumber"))) end++;
                paragraphs(output,documentId,generation,blocks.subList(i,end),spec,parsed.parserVersion()); i=end;
            } else {
                if(block.blockType()==BlockType.TABLE) table(output,documentId,generation,block,spec,parsed.parserVersion());
                else lines(output,documentId,generation,block,spec,parsed.parserVersion());
                i++;
            }
        }
        if(output.isEmpty()) throw empty();
        return List.copyOf(output);
    }
    private void paragraphs(List<Chunk> out,UUID doc,long generation,List<Block> blocks,Splitter spec,String parserVersion) {
        var text=new StringBuilder(); var starts=new ArrayList<Integer>();var ends=new ArrayList<Integer>();
        for(var b:blocks) { if(!text.isEmpty())text.append("\n\n");starts.add(text.length());text.append(b.content());ends.add(text.length()); }
        String value=text.toString(); int start=0;
        while(start<value.length()) {
            int end=value.offsetByCodePoints(start,Math.min(spec.maxChars(),value.codePointCount(start,value.length())));
            if(end<value.length()) {
                int boundary=value.lastIndexOf("\n\n",end);
                if(boundary<start) boundary=value.lastIndexOf(' ',end);
                if(boundary>start && value.codePointCount(start,boundary)>Math.max(spec.overlapChars(),spec.maxChars()/2)) end=boundary;
            }
            var sources=new ArrayList<Block>();
            for(int i=0;i<blocks.size();i++) if(starts.get(i)<end && ends.get(i)>start) sources.add(blocks.get(i));
            String content=value.substring(start,end).strip();
            if(!content.isBlank())emit(out,doc,generation,content,content,sources,
                    Map.of("textStart",start,"textEnd",end,"offsetUnit","UTF16","overlapChars",start==0?0:spec.overlapChars()),parserVersion);
            if(end==value.length())break;
            start=value.offsetByCodePoints(end,-Math.min(spec.overlapChars(),value.codePointCount(start,end)-1));
        }
    }
    private void lines(List<Chunk> out,UUID doc,long generation,Block block,Splitter spec,String parserVersion) {
        int soft=Math.min(limits.hardChunkChars(),spec.maxChars()+spec.toleranceChars());
        if(chars(block.content())<=soft) { emit(out,doc,generation,block.content(),block.content(),List.of(block),Map.of(),parserVersion);return; }
        String[] lines=block.content().split("\n",-1); int first=0;var pending=new StringBuilder();
        for(int i=0;i<lines.length;i++) {
            if(chars(lines[i])>limits.hardChunkChars()) throw tooLarge();
            if(!pending.isEmpty() && chars(pending.toString())+1+chars(lines[i])>spec.maxChars()) {
                emit(out,doc,generation,pending.toString(),pending.toString(),List.of(block),Map.of("fragmentLineStart",first+1,"fragmentLineEnd",i),parserVersion);
                pending.setLength(0);first=i;
            }
            if(!pending.isEmpty())pending.append('\n');pending.append(lines[i]);
        }
        if(!pending.isEmpty())emit(out,doc,generation,pending.toString(),pending.toString(),List.of(block),Map.of("fragmentLineStart",first+1,"fragmentLineEnd",lines.length),parserVersion);
    }
    private void table(List<Chunk> out,UUID doc,long generation,Block block,Splitter spec,String parserVersion) {
        var rows=block.tableRows();int start=0;
        do {
            var group=new ArrayList<List<String>>();
            while(start+group.size()<rows.size() && group.size()<spec.tableRows()) {
                var candidate=new ArrayList<>(group);candidate.add(rows.get(start+group.size()));
                int count=chars(MarkdownDocumentParser.table(block.tableHeader(),candidate));
                if(!group.isEmpty() && count>Math.min(limits.hardChunkChars(),spec.maxChars()+spec.toleranceChars()))break;
                if(count>limits.hardChunkChars())throw tooLarge();
                group=candidate;
            }
            String content=MarkdownDocumentParser.table(block.tableHeader(),group);var semantic=new StringBuilder();
            for(var row:group) {
                if(!semantic.isEmpty())semantic.append('\n');
                for(int i=0;i<block.tableHeader().size();i++) {
                    if(i>0)semantic.append("；");semantic.append(block.tableHeader().get(i)).append("：").append(i<row.size()?row.get(i):"");
                }
            }
            if(semantic.isEmpty())semantic.append(String.join("；",block.tableHeader()));
            var location=new LinkedHashMap<String,Object>();location.put("rowStart",start+1);location.put("rowEnd",start+group.size());location.put("headerRepeated",true);
            if(block.source().get("dataRowNumbers") instanceof List<?> numbers && !group.isEmpty()) {
                var selected=numbers.subList(start,start+group.size());location.put("rowNumbers",selected);location.put("rowStart",selected.get(0));location.put("rowEnd",selected.get(selected.size()-1));
            }
            for(String field:List.of("sheetName","tableName","headerRow","rowNumberUnit","formulaPolicy"))if(block.source().containsKey(field))location.put(field,block.source().get(field));
            emit(out,doc,generation,content,semantic.toString(),List.of(block),location,parserVersion);
            start+=group.size();
        } while(start<rows.size());
    }
    private void emit(List<Chunk> out,UUID doc,long generation,String content,String embedding,List<Block> blocks,Map<String,Object> extra,String parserVersion) {
        if(content.isBlank())return;
        if(chars(content)>limits.hardChunkChars())throw tooLarge();
        if(out.size()>=limits.maxChunks())throw new KnowledgeBaseException(422,"KB_TOO_MANY_CHUNKS","Document exceeds configured chunk count");
        List<String> path=extra.containsKey("wholeDocument")?List.of():blocks.get(0).chapterPath();
        String prefix=path.isEmpty()?"":"章节："+String.join(" > ",path)+"\n";
        if(!embedding.startsWith(prefix))embedding=prefix+embedding;
        if(chars(embedding)>limits.maxEmbeddingChars())throw tooLarge();
        var metadata=new LinkedHashMap<String,Object>();metadata.put("chapterPath",path);metadata.put("parserVersion",parserVersion);
        metadata.put("blockType",extra.containsKey("wholeDocument")?"DOCUMENT":blocks.get(0).blockType().name());
        metadata.put("sourceBlocks",blocks.stream().map(b->{var source=new LinkedHashMap<>(b.source());source.remove("dataRowNumbers");source.put("blockIndex",b.index());return source;}).toList());
        metadata.putAll(extra);
        String hash=EmbeddingCatalog.sha256(content);
        UUID id=UUID.nameUUIDFromBytes((doc+":"+generation+":"+out.size()+":"+hash).getBytes(StandardCharsets.UTF_8));
        out.add(new Chunk(id,out.size(),content,embedding,hash,chars(content),Map.copyOf(metadata)));
    }
    private static int chars(String text) { return text.codePointCount(0,text.length()); }
    private static KnowledgeBaseException empty() { return new KnowledgeBaseException(422,"KB_EMPTY_CONTENT","Document has no indexable content"); }
    private static KnowledgeBaseException tooLarge() { return new KnowledgeBaseException(422,"KB_STRUCTURE_TOO_LARGE","Structure exceeds hard content/embedding budget; adjust config or source"); }
    private static KnowledgeBaseException invalid(String message) { return new KnowledgeBaseException(400,"KB_INVALID_INPUT",message); }
}
