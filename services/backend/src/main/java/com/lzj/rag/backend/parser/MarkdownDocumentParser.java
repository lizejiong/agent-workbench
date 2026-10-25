package com.lzj.rag.backend.parser;

import org.commonmark.node.*;
import org.commonmark.parser.*;
import org.commonmark.ext.gfm.tables.*;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.lzj.rag.backend.parser.ParsedDocument.*;
import com.lzj.rag.backend.parser.ParsedDocument.Block;

@Component
public class MarkdownDocumentParser implements DocumentParser {
    private final Parser parser=Parser.builder().extensions(List.of(TablesExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
    public String profile() { return "MARKDOWN"; }
    public Set<String> mimeTypes() { return Set.of("text/plain","text/markdown","text/x-web-markdown"); }
    public ParsedDocument parse(String text) {
        var blocks=new ArrayList<Block>(); var headings=new TreeMap<Integer,String>();
        walk(parser.parse(text),headings,blocks,text.split("\n",-1));
        return new ParsedDocument(text,List.copyOf(blocks),"commonmark-0.27.1-gfm-v1");
    }
    private void walk(Node parent, TreeMap<Integer,String> headings,List<Block> blocks,String[] lines) {
        for(Node node=parent.getFirstChild();node!=null;node=node.getNext()) {
            BlockType type; String content; var source=new LinkedHashMap<String,Object>();
            List<String> header=List.of(); List<List<String>> rows=List.of();
            if(node instanceof Heading heading) {
                headings.tailMap(heading.getLevel()).clear(); content=inline(node); headings.put(heading.getLevel(),content); type=BlockType.HEADING;
            } else if(node instanceof TableBlock) {
                type=BlockType.TABLE; var all=new ArrayList<List<String>>(); collectRows(node,all);
                if(all.isEmpty()) continue;
                header=all.get(0); rows=List.copyOf(all.subList(1,all.size())); content=table(header,rows);
            } else if(node instanceof FencedCodeBlock code) { type=BlockType.CODE; content=code.getLiteral().stripTrailing(); source.put("language",code.getInfo()); }
            else if(node instanceof IndentedCodeBlock code) { type=BlockType.CODE;content=code.getLiteral().stripTrailing(); }
            else if(node instanceof ListBlock) { type=BlockType.LIST;content=original(node,lines); }
            else if(node instanceof Paragraph) { type=BlockType.PARAGRAPH;content=inline(node); }
            else { walk(node,headings,blocks,lines);continue; }
            if(content.isBlank()) continue;
            var spans=node.getSourceSpans();
            if(!spans.isEmpty()) {
                source.put("lineStart",spans.get(0).getLineIndex()+1);
                source.put("lineEnd",spans.get(spans.size()-1).getLineIndex()+1);
            }
            blocks.add(new Block(blocks.size(),type,content,List.copyOf(headings.values()),Map.copyOf(source),header,rows));
        }
    }
    private static void collectRows(Node node,List<List<String>> all) {
        if(node instanceof TableRow) {
            var row=new ArrayList<String>();for(Node cell=node.getFirstChild();cell!=null;cell=cell.getNext()) row.add(inline(cell));
            all.add(List.copyOf(row)); return;
        }
        for(Node child=node.getFirstChild();child!=null;child=child.getNext()) collectRows(child,all);
    }
    private static String original(Node node,String[] lines) {
        var spans=node.getSourceSpans(); if(spans.isEmpty()) return inline(node);
        int first=spans.get(0).getLineIndex(),last=spans.get(spans.size()-1).getLineIndex();
        return String.join("\n",Arrays.copyOfRange(lines,first,Math.min(last+1,lines.length)));
    }
    private static String inline(Node node) {
        var result=new StringBuilder(); append(node,result); return result.toString().strip();
    }
    private static void append(Node node,StringBuilder result) {
        if(node instanceof Text text) result.append(text.getLiteral());
        else if(node instanceof Code code) result.append(code.getLiteral());
        else if(node instanceof SoftLineBreak || node instanceof HardLineBreak) result.append('\n');
        else for(Node child=node.getFirstChild();child!=null;child=child.getNext()) append(child,result);
    }
    public static String table(List<String> header,List<List<String>> rows) {
        String first="| "+String.join(" | ",header.stream().map(MarkdownDocumentParser::escape).toList())+" |";
        var result=new StringBuilder(first).append('\n').append("| ").append(String.join(" | ",Collections.nCopies(header.size(),"---"))).append(" |");
        for(var row:rows) result.append('\n').append("| ").append(String.join(" | ",row.stream().map(MarkdownDocumentParser::escape).toList())).append(" |");
        return result.toString();
    }
    private static String escape(String text) { return text.replace("\\","\\\\").replace("|","\\|").replace("\n","<br>"); }
}
