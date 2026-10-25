package com.lzj.rag.backend.parser;

import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.OfficeParser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.commons.csv.*;
import org.xml.sax.ContentHandler;
import java.io.*;
import java.util.*;
import static com.lzj.rag.backend.parser.ParsedDocument.*;

/** Invoked only in the bounded subprocess. No OCR executable, external workbook or embedded attachment execution. */
final class LocalFormatExtractor {
    static ParsedDocument parse(ParserInput input,FormatProperties config)throws Exception {
        if(input.mime().equals("application/x-kb-pdf-inspection")) {
            try(var pdf=org.apache.pdfbox.Loader.loadPDF(input.path().toFile())){if(pdf.getNumberOfPages()>config.maxPages())throw limit();}
            return new ParsedDocument("",List.of(),"pdf-inspection-v1");
        }
        if(input.mime().equals("text/csv") || SupportedFiles.extension(input.filename()).equals("csv"))return csv(input,config);
        if(SupportedFiles.EXCEL.contains(input.mime()))return excel(input,config);
        var context=new ParseContext();var pdfConfig=new PDFParserConfig();pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdfConfig.setExtractInlineImages(false);pdfConfig.setExtractAcroFormContent(false);context.set(PDFParserConfig.class,pdfConfig);
        context.set(EmbeddedDocumentExtractor.class,new EmbeddedDocumentExtractor(){
            public boolean shouldParseEmbedded(Metadata m){return false;}
            public void parseEmbedded(InputStream in,ContentHandler handler,Metadata m,boolean html){ }
        });
        var handler=new StructuredContentHandler(input.maxParsedChars(),config.maxPages(),input.deadline());
        org.apache.tika.parser.Parser parser=input.mime().equals("application/pdf")?new PDFParser():input.mime().contains("openxmlformats")?new OOXMLParser():new OfficeParser();
        try(var stream=java.nio.file.Files.newInputStream(input.path())){parser.parse(stream,handler,new Metadata(),context);}
        var parsed=handler.result("tika-3.3.3-fast-no-ocr-v1");
        if(parsed.blocks().stream().noneMatch(b->b.blockType()!=BlockType.HEADING && !b.content().isBlank()))
            throw new KnowledgeBaseException(422,input.mime().equals("application/pdf")?"KB_OCR_REQUIRED":"KB_EMPTY_CONTENT","No extractable text; scanned PDF requires configured fidelity/OCR");
        return parsed;
    }
    private static ParsedDocument excel(ParserInput input,FormatProperties config)throws Exception {
        ZipSecureFile.setMinInflateRatio(0.01);ZipSecureFile.setMaxEntrySize(16777216);ZipSecureFile.setMaxTextSize(input.maxParsedChars());
        var blocks=new ArrayList<Block>();var text=new StringBuilder();
        try(var book=WorkbookFactory.create(input.path().toFile(),null,true)) {
            if(book.getNumberOfSheets()>config.maxSheets())throw limit();var format=new DataFormatter(Locale.ROOT);var evaluator=book.getCreationHelper().createFormulaEvaluator();
            evaluator.setIgnoreMissingWorkbooks(false);int totalRows=0,totalChars=0;
            for(var sheet:book) {
                if(sheet.getLastRowNum()+1>config.maxRows())throw limit();
                List<String> header=null;var rows=new ArrayList<List<String>>();var numbers=new ArrayList<Integer>();int headerRow=0;
                for(var row:sheet) {
                    if(++totalRows>config.maxRows() || row.getLastCellNum()>config.maxColumns())throw limit();
                    int width=header==null?Math.max(0,row.getLastCellNum()):header.size();
                    if(header!=null && row.getLastCellNum()>width)throw new KnowledgeBaseException(422,"KB_TABLE_ROW_WIDTH","Data extends beyond header columns");
                    var values=new ArrayList<String>();
                    for(int i=0;i<width;i++) {
                        Cell cell=row.getCell(i,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);String value="";
                        if(cell!=null)try {
                            if(cell.getCellType()==CellType.FORMULA && evaluator.evaluate(cell).getCellType()==CellType.ERROR)
                                throw new IllegalArgumentException("Formula evaluated to error");
                            value=format.formatCellValue(cell,evaluator);
                        }catch(RuntimeException error){throw new KnowledgeBaseException(422,"KB_FORMULA_EVALUATION_FAILED","Formula cannot be evaluated; resolve errors or replace it with a reviewed value");}
                        totalChars+=value.length();if(totalChars>input.maxParsedChars())throw limit();values.add(value);
                    }
                    if(values.stream().allMatch(String::isBlank))continue;
                    if(header==null){header=TableSupport.header(values);headerRow=row.getRowNum()+1;}
                    else{rows.add(List.copyOf(values));numbers.add(row.getRowNum()+1);}
                }
                if(header!=null)addTable(blocks,text,header,rows,Map.of("sheetName",sheet.getSheetName(),"headerRow",headerRow,"dataRowNumbers",numbers,"formulaPolicy","EVALUATE"),input.maxParsedChars());
            }
        }return new ParsedDocument(text.toString(),List.copyOf(blocks),"poi-5.5.1-evaluate-v1");
    }
    private static ParsedDocument csv(ParserInput input,FormatProperties config)throws IOException {
        var blocks=new ArrayList<Block>();var text=new StringBuilder();
        // Strict UTF-8, RFC4180 including quoted newlines; '=' is ordinary text, never an Excel formula.
        try(var raw=SupportedFiles.utf8(input.path());var reader=new PushbackReader(raw,1)) {
            int first=reader.read();if(first!=-1 && first!=0xfeff)reader.unread(first);
            try(var parser=CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false).get().parse(reader)) {
                List<String> header=null;var rows=new ArrayList<List<String>>();var numbers=new ArrayList<Integer>();int chars=0;
                for(var record:parser) {
                    if(record.getRecordNumber()>config.maxRows() || record.size()>config.maxColumns())throw limit();
                    var values=new ArrayList<String>();for(String v:record){chars+=v.length();if(chars>input.maxParsedChars())throw limit();values.add(v);}
                    if(header==null){header=TableSupport.header(values);continue;}
                    if(values.size()!=header.size())throw new KnowledgeBaseException(422,"KB_CSV_ROW_WIDTH","CSV row width must match its header; empty fields must retain delimiters");
                    rows.add(List.copyOf(values));numbers.add(Math.toIntExact(record.getRecordNumber()));
                }
                if(header!=null)addTable(blocks,text,header,rows,Map.of("tableName","CSV","headerRow",1,"dataRowNumbers",numbers,"formulaPolicy","LITERAL_TEXT","rowNumberUnit","CSV_RECORD"),input.maxParsedChars());
            }
        }return new ParsedDocument(text.toString(),List.copyOf(blocks),"commons-csv-1.14.1-rfc4180-v1");
    }
    private static void addTable(List<Block> blocks,StringBuilder text,List<String> header,List<List<String>> rows,Map<String,Object> source,int max) {
        String content=MarkdownDocumentParser.table(header,rows);if(text.length()+content.length()>max)throw limit();if(!text.isEmpty())text.append("\n\n");text.append(content);
        String name=String.valueOf(source.getOrDefault("sheetName",source.getOrDefault("tableName","Table")));
        blocks.add(new Block(blocks.size(),BlockType.TABLE,content,List.of(name),source,List.copyOf(header),List.copyOf(rows)));
    }
    private static KnowledgeBaseException limit(){return new KnowledgeBaseException(422,"KB_PARSE_LIMIT","Spreadsheet/table exceeds configured row/column/sheet/text limits");}
}
