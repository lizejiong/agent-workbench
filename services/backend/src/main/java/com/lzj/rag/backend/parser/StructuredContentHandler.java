package com.lzj.rag.backend.parser;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;
import java.time.Instant;
import java.util.*;
import static com.lzj.rag.backend.parser.ParsedDocument.*;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;

/** Consume Tika XHTML without following links or materializing binary/embedded objects. */
final class StructuredContentHandler extends DefaultHandler {
    private final int maxChars,maxPages;private final Instant deadline;
    private final List<Block> blocks=new ArrayList<>();private final List<String> headings=new ArrayList<>();
    private final StringBuilder all=new StringBuilder(),pending=new StringBuilder();private int chars,page,slide,tableDepth;
    private String tag;private List<List<String>> rows;private List<String> row;private StringBuilder cell;
    StructuredContentHandler(int maxChars,int maxPages,Instant deadline){this.maxChars=maxChars;this.maxPages=maxPages;this.deadline=deadline;}
    private void check(){if(Thread.currentThread().isInterrupted() || Instant.now().isAfter(deadline))throw new KnowledgeBaseException(504,"KB_PARSE_TIMEOUT","Local parse budget expired");}
    @Override public void startElement(String uri,String local,String name,Attributes attributes) {
        check();String t=local.isEmpty()?name:local;String cls=attributes.getValue("class");
        if(t.equals("div") && "page".equals(cls)){flush();if(++page>maxPages)throw limit();}
        if(t.equals("div") && "slide-content".equals(cls)){flush();if(++slide>maxPages)throw limit();}
        if(t.equals("table")){flush();if(++tableDepth>1)throw new KnowledgeBaseException(422,"KB_TABLE_STRUCTURE_UNSUPPORTED","Nested tables require an explicit layout parser");rows=new ArrayList<>();}
        if(tableDepth>0) {
            if(t.equals("tr"))row=new ArrayList<>();
            if(Set.of("td","th").contains(t)){cell=new StringBuilder();for(String key:List.of("colspan","rowspan")){String span=attributes.getValue(key);if(span!=null && !span.equals("1"))throw new KnowledgeBaseException(422,"KB_TABLE_STRUCTURE_UNSUPPORTED","Spanning Office tables need layout normalization; current fidelity route accepts PDF only");}}
            return;
        }
        if(t.matches("h[1-6]") || Set.of("p","li","pre").contains(t)){flush();tag=t;}
    }
    @Override public void characters(char[] ch,int start,int length) {
        check();if((chars+=length)>maxChars)throw limit();
        if(cell!=null)cell.append(ch,start,length);else if(tableDepth==0 && tag!=null)pending.append(ch,start,length);
    }
    @Override public void endElement(String uri,String local,String name) {
        String t=local.isEmpty()?name:local;
        if(tableDepth>0) {
            if(Set.of("td","th").contains(t) && cell!=null){if(row!=null)row.add(cell.toString().strip());cell=null;}
            if(t.equals("tr") && row!=null){rows.add(List.copyOf(row));row=null;}
            if(t.equals("table")){
                tableDepth--;if(rows!=null && !rows.isEmpty()){
                    var header=TableSupport.header(rows.get(0));var data=rows.subList(1,rows.size());
                    int width=header.size();for(var r:data)if(r.size()!=width)throw new KnowledgeBaseException(422,"KB_TABLE_STRUCTURE_UNSUPPORTED","Irregular Office table requires fidelity parsing");
                    var metadata=source();metadata.put("headerPolicy","FIRST_ROW");add(BlockType.TABLE,MarkdownDocumentParser.table(header,data),metadata,header,data);
                }rows=null;
            }return;
        }
        if(t.equals(tag))flush();
    }
    private Map<String,Object> source(){var result=new LinkedHashMap<String,Object>();if(page>0)result.put("pageNumber",page);if(slide>0)result.put("slideNumber",slide);return result;}
    private void flush() {
        if(tag==null)return;String value=pending.toString().strip();pending.setLength(0);String current=tag;tag=null;if(value.isBlank())return;
        BlockType type=BlockType.PARAGRAPH;
        if(current.matches("h[1-6]")){int level=current.charAt(1)-'0';while(headings.size()>=level)headings.remove(headings.size()-1);headings.add(value);type=BlockType.HEADING;}
        else if(current.equals("li"))type=BlockType.LIST;else if(current.equals("pre"))type=BlockType.CODE;
        add(type,value,source(),List.of(),List.of());
    }
    private void add(BlockType type,String content,Map<String,Object> metadata,List<String> header,List<List<String>> data) {
        if(!all.isEmpty())all.append("\n\n");all.append(content);if(all.length()>maxChars)throw limit();
        blocks.add(new Block(blocks.size(),type,content,List.copyOf(headings),Map.copyOf(metadata),List.copyOf(header),List.copyOf(data)));
    }
    ParsedDocument result(String version){flush();return new ParsedDocument(all.toString(),List.copyOf(blocks),version);}
    private KnowledgeBaseException limit(){return new KnowledgeBaseException(422,"KB_PARSE_LIMIT","Document exceeds page/text parsing limits");}
}
