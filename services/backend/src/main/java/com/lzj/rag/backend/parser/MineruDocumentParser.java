package com.lzj.rag.backend.parser;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.model.AiEmbeddingClient;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.lzj.rag.backend.parser.ParsedDocument.*;

/** Explicit 2.7.6 self-hosted synchronous contract. Does not assume the incompatible MinerU 4.x V1 API. */
@Component
public class MineruDocumentParser implements DocumentParser {
    private final FormatProperties config;private final ObjectMapper json;private final LocalFormatParser local;
    private final HttpClient client;private final Semaphore permits;
    public MineruDocumentParser(FormatProperties config,ObjectMapper json,LocalFormatParser local) {
        this.config=config;this.json=json;this.local=local;permits=new Semaphore(config.mineru().concurrency());
        client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(config.mineru().connectTimeout()).build();
        var c=config.mineru();
        if(c.enabled()) {
            URI uri=URI.create(c.baseUrl());
            if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || !"mineru-2.7.6-file-parse".equals(c.protocol()) || !Set.of("pipeline","hybrid-auto-engine","vlm-auto-engine").contains(c.backend())
                    || !c.language().matches("[a-z_]{2,20}") || c.timeout().isNegative() || c.timeout().isZero() || c.timeout().compareTo(Duration.ofMinutes(5))>0)
                throw new IllegalArgumentException("Invalid MinerU endpoint/protocol/backend/language/time budget");
        }
    }
    public String profile(){return "BINARY";}
    public String parseProfile(){return "fidelity";}
    public Set<String> mimeTypes(){return Set.of("application/pdf");}
    public ParsedDocument parse(String text){throw unavailable();}
    @Override public ParsedDocument parse(ParserInput input) {
        if(!config.mineru().enabled())throw unavailable();
        local.validatePdf(input); // count/reject oversized/encrypted PDFs before sending bytes; never silently truncate pages
        boolean acquired=false;
        Instant deadline=Instant.now().plus(config.mineru().timeout());if(input.deadline().isBefore(deadline))deadline=input.deadline();
        try {
            long wait=Duration.between(Instant.now(),deadline).toMillis();if(wait<=0 || !(acquired=permits.tryAcquire(wait,TimeUnit.MILLISECONDS)))throw timeout();
            var schema=call("GET","/openapi.json",HttpRequest.BodyPublishers.noBody(),"application/json",input.requestId(),deadline);
            var operation=schema.at("/paths/~1file_parse/post/requestBody/content/multipart~1form-data/schema");
            JsonNode properties=operation.path("properties");
            if(operation.has("$ref"))properties=schema.path("components").path("schemas").path(operation.path("$ref").asText().replace("#/components/schemas/","")).path("properties");
            for(String field:List.of("files","return_content_list","return_images","return_md","response_format_zip","backend"))if(!properties.has(field))throw protocol();
            String boundary="kb-"+UUID.randomUUID();var parts=new ArrayList<HttpRequest.BodyPublisher>();
            var fields=Map.of("backend",config.mineru().backend(),"parse_method","auto","lang_list",config.mineru().language(),"return_md","false",
                    "return_images","false","return_content_list","true","response_format_zip","false","formula_enable","true","table_enable","true");
            for(var entry:fields.entrySet())parts.add(HttpRequest.BodyPublishers.ofString("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+entry.getKey()+"\"\r\n\r\n"+entry.getValue()+"\r\n"));
            parts.add(HttpRequest.BodyPublishers.ofString("--"+boundary+"\r\nContent-Disposition: form-data; name=\"files\"; filename=\"document.pdf\"\r\nContent-Type: application/pdf\r\n\r\n"));
            parts.add(HttpRequest.BodyPublishers.ofFile(input.path()));parts.add(HttpRequest.BodyPublishers.ofString("\r\n--"+boundary+"--\r\n"));
            var response=call("POST","/file_parse",HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new)),"multipart/form-data; boundary="+boundary,input.requestId(),deadline);
            if(!"2.7.6".equals(response.path("version").asText()) || !config.mineru().backend().equals(response.path("backend").asText()) || response.path("results").size()!=1)throw protocol();
            var list=response.path("results").path("document").path("content_list");
            if(!list.isTextual())throw protocol();list=json.readTree(list.textValue());if(!list.isArray())throw protocol();
            return structured(list,input.maxParsedChars());
        }catch(KnowledgeBaseException e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw timeout();}
        catch(java.io.IOException e){throw protocol();}
        finally{if(acquired)permits.release();}
    }
    private JsonNode call(String method,String path,HttpRequest.BodyPublisher body,String contentType,String requestId,Instant deadline) {
        long timeout=Duration.between(Instant.now(),deadline).toMillis();if(timeout<=0)throw timeout();
        var builder=HttpRequest.newBuilder(URI.create(config.mineru().baseUrl().replaceAll("/+$","")+path)).timeout(Duration.ofMillis(timeout))
                .header("Content-Type",contentType).header("X-Request-Id",requestId).method(method,body);
        if(!config.mineru().token().isBlank())builder.header("Authorization","Bearer "+config.mineru().token());
        var future=client.sendAsync(builder.build(),info->new AiEmbeddingClient.LimitedBody(config.maxResultBytes()));
        try {
            var response=future.get(timeout,TimeUnit.MILLISECONDS);int status=response.statusCode();
            if(status==408 || status==504)throw timeout();
            if(status==429 || status>=500)throw new KnowledgeBaseException(503,"KB_PARSE_SERVICE_UNAVAILABLE","MinerU temporarily unavailable; bounded job retries apply");
            if(status!=200)throw protocol();return json.readTree(response.body());
        }catch(TimeoutException e){future.cancel(true);throw timeout();}
        catch(InterruptedException e){future.cancel(true);Thread.currentThread().interrupt();throw timeout();}
        catch(ExecutionException e){if(e.getCause() instanceof java.net.http.HttpTimeoutException)throw timeout();if(e.getCause() instanceof IllegalStateException)throw protocol();throw new KnowledgeBaseException(503,"KB_PARSE_SERVICE_UNAVAILABLE","MinerU connection failed");}
        catch(java.io.IOException e){throw protocol();}
    }
    private ParsedDocument structured(JsonNode entries,int max) {
        var blocks=new ArrayList<Block>();var text=new StringBuilder();var headings=new ArrayList<String>();
        for(var item:entries) {
            if(!item.path("page_idx").isIntegralNumber() || !item.path("page_idx").canConvertToInt() || item.path("page_idx").asInt()<0 || item.path("page_idx").asInt()>=config.maxPages())throw protocol();
            var source=new LinkedHashMap<String,Object>();source.put("pageNumber",item.path("page_idx").asInt()+1);source.put("provenance","MINERU_2_7_6");
            if(item.path("bbox").isArray()) {
                var bbox=new ArrayList<Double>();if(item.path("bbox").size()!=4)throw protocol();
                for(var n:item.path("bbox")){if(!n.isNumber() || !Double.isFinite(n.doubleValue()) || n.doubleValue()<0)throw protocol();bbox.add(n.doubleValue());}
                if(bbox.get(0)>bbox.get(2) || bbox.get(1)>bbox.get(3))throw protocol();
                source.put("bbox",bbox);
            }
            String type=item.path("type").asText(),content;BlockType kind=BlockType.PARAGRAPH;List<String> header=List.of();List<List<String>> rows=List.of();
            switch(type) {
                case "text" -> {if(!item.path("text").isTextual() || (item.hasNonNull("text_level") && (!item.path("text_level").isIntegralNumber() || !item.path("text_level").canConvertToInt())))throw protocol();content=item.path("text").asText("");int level=item.path("text_level").asInt(0);if(level<0 || level>6)throw protocol();if(level>0){while(headings.size()>=level)headings.remove(headings.size()-1);headings.add(content);kind=BlockType.HEADING;}}
                case "equation" -> {if(!item.path("text").isTextual())throw protocol();content=item.path("text").asText("");kind=BlockType.CODE;}
                case "table" -> {if(!item.path("table_body").isTextual())throw protocol();var grid=htmlTable(item.path("table_body").asText(""),max);if(grid.isEmpty())throw protocol();header=TableSupport.header(grid.get(0));rows=List.copyOf(grid.subList(1,grid.size()));content=MarkdownDocumentParser.table(header,rows);kind=BlockType.TABLE;source.put("headerPolicy","FIRST_ROW");}
                case "image" -> {var captions=new ArrayList<String>();for(String key:List.of("image_caption","image_footnote"))if(item.path(key).isArray())for(var value:item.path(key))captions.add(value.asText());content=String.join("\n",captions);source.put("provenance","MINERU_IMAGE_CAPTION");}
                default -> throw new KnowledgeBaseException(502,"KB_MINERU_CONTENT_UNSUPPORTED","MinerU returned an unsupported content block; no silent downgrade");
            }
            if(content.isBlank())continue;if(text.length()+content.length()>max)throw new KnowledgeBaseException(422,"KB_PARSE_LIMIT","Layout text exceeds budget");
            if(!text.isEmpty())text.append("\n\n");text.append(content);if(blocks.size()>=config.maxRows())throw protocol();
            blocks.add(new Block(blocks.size(),kind,content,List.copyOf(headings),Map.copyOf(source),header,rows));
        }
        if(blocks.stream().noneMatch(b->b.blockType()!=BlockType.HEADING))throw new KnowledgeBaseException(422,"KB_EMPTY_CONTENT","MinerU returned no indexable body");
        return new ParsedDocument(text.toString(),List.copyOf(blocks),"mineru-2.7.6-content-list-v1");
    }
    private List<List<String>> htmlTable(String html,int max) {
        if(html.length()>max)throw protocol();var tables=Jsoup.parseBodyFragment(html).select("table");if(tables.size()!=1)throw protocol();
        var out=new ArrayList<List<String>>();int chars=0,r=0;
        for(var tr:tables.first().select("tr")) {
            if(r>=config.maxRows())throw protocol();while(out.size()<=r)out.add(new ArrayList<>());int col=0;
            for(var cell:tr.children()) {
                if(!Set.of("td","th").contains(cell.tagName()))continue;
                int rs=span(cell.attr("rowspan")),cs=span(cell.attr("colspan"));String value=cell.text();
                while(col<out.get(r).size() && out.get(r).get(col)!=null)col++;
                if(r+rs>config.maxRows() || col+cs>config.maxColumns())throw protocol();
                for(int a=r;a<r+rs;a++) {
                    while(out.size()<=a)out.add(new ArrayList<>());var row=out.get(a);
                    while(row.size()<col+cs)row.add(null);
                    for(int c=col;c<col+cs;c++){if(row.get(c)!=null)throw protocol();row.set(c,value);chars+=value.length();if(chars>max)throw protocol();}
                }col+=cs;
            }r++;
        }
        if(out.size()!=r)throw protocol(); // A rowspan may not manufacture rows beyond the actual table.
        int width=out.stream().mapToInt(List::size).max().orElse(0);if(width==0)throw protocol();
        return out.stream().map(row->{while(row.size()<width)row.add("");return row.stream().map(v->v==null?"":v).toList();}).toList();
    }
    private int span(String value){if(value.isBlank())return 1;try{int n=Integer.parseInt(value);if(n<1 || n>config.maxRows())throw protocol();return n;}catch(NumberFormatException e){throw protocol();}}
    private static KnowledgeBaseException unavailable(){return new KnowledgeBaseException(503,"KB_MINERU_UNAVAILABLE","PDF fidelity/OCR requires configured MinerU 2.7.6 file_parse service");}
    private static KnowledgeBaseException protocol(){return new KnowledgeBaseException(502,"KB_MINERU_PROTOCOL_ERROR","MinerU deployment/response does not match the configured 2.7.6 content-list contract");}
    private static KnowledgeBaseException timeout(){return new KnowledgeBaseException(504,"KB_EXTERNAL_PARSE_TIMEOUT","MinerU exceeded the bounded synchronous request budget; no fallback or unbounded polling");}
}
