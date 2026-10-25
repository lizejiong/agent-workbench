package com.lzj.rag.backend.parser;

import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Extension is a compatibility check, never the source of the detected media type. No SVG/macros/archives. */
public final class SupportedFiles {
    private SupportedFiles() { }
    public static final Set<String> OFFICE=Set.of("application/pdf","application/msword","application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.presentationml.presentation");
    public static final Set<String> EXCEL=Set.of("application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    public static final Set<String> IMAGES=Set.of("image/png","image/jpeg");
    private static final Set<String> TEXT=Set.of("text/plain","text/markdown","text/x-web-markdown");
    private static final Map<String,Set<String>> TYPES=Map.ofEntries(Map.entry("txt",TEXT),Map.entry("md",TEXT),Map.entry("markdown",TEXT),
            Map.entry("csv",Set.of("text/plain","text/csv","application/csv")),Map.entry("pdf",Set.of("application/pdf")),
            Map.entry("doc",Set.of("application/msword")),Map.entry("docx",Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
            Map.entry("ppt",Set.of("application/vnd.ms-powerpoint")),Map.entry("pptx",Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation")),
            Map.entry("xls",Set.of("application/vnd.ms-excel")),Map.entry("xlsx",Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
            Map.entry("png",Set.of("image/png")),Map.entry("jpg",Set.of("image/jpeg")),Map.entry("jpeg",Set.of("image/jpeg")));
    public static String extension(String filename){int i=filename.lastIndexOf('.');return i<0?"":filename.substring(i+1).toLowerCase(Locale.ROOT);}
    public static boolean text(String filename){return Set.of("txt","md","markdown","csv").contains(extension(filename));}
    public static void extensionAllowed(String filename){if(!TYPES.containsKey(extension(filename)))throw unsupported();}
    public static String detect(Path file)throws IOException {
        // Container detection may inspect ZIP members; bound expansion before allowing Tika's detector to run.
        try(var in=Files.newInputStream(file)) {
            byte[] magic=in.readNBytes(4);
            if(magic.length==4 && magic[0]=='P' && magic[1]=='K')checkZip(file);
        }
        try(var in=TikaInputStream.get(file)){return new Tika().detect(in);}
    }
    public static void compatible(String filename,String mime){if(!TYPES.getOrDefault(extension(filename),Set.of()).contains(mime))throw unsupported();}
    private static void checkZip(Path file)throws IOException {
        try(var zip=new ZipFile(file.toFile())) {
            var entries=zip.entries();long total=0;int count=0;
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement();long size=entry.getSize(),compressed=entry.getCompressedSize();
                if(++count>4096 || size<0 || (total+=size)>67108864 || size>16777216 || (size>1048576 && (compressed<=0 || (double)size/compressed>100)))
                    throw new KnowledgeBaseException(422,"KB_CONTAINER_LIMIT","Office archive exceeds bounded expansion limits");
            }
        }
    }
    public static Reader utf8(Path file)throws IOException {
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        return new InputStreamReader(Files.newInputStream(file),decoder);
    }
    public static String text(Path file,int max)throws IOException {
        var text=new StringBuilder();try(var reader=utf8(file)){char[] b=new char[4096];int n;while((n=reader.read(b))!=-1){if(text.length()+n>max)throw new KnowledgeBaseException(422,"KB_SOURCE_TOO_LARGE","Text exceeds parsed character budget");text.append(b,0,n);}}
        String value=text.toString();if(value.startsWith("\ufeff"))value=value.substring(1);return value.replace("\r\n","\n").replace('\r','\n');
    }
    private static KnowledgeBaseException unsupported(){return new KnowledgeBaseException(415,"KB_UNSUPPORTED_FILE","File extension and detected media type must match the supported format matrix");}
}
