package com.lzj.rag.backend.parser;

import com.lzj.rag.backend.model.VlmClient;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.util.*;
import static com.lzj.rag.backend.parser.ParsedDocument.*;

@Component
public class ImageDocumentParser implements DocumentParser {
    private final VlmClient vlm;private final FormatProperties config;
    public ImageDocumentParser(VlmClient vlm,FormatProperties config){this.vlm=vlm;this.config=config;}
    public String profile(){return "BINARY";}
    public Set<String> mimeTypes(){return SupportedFiles.IMAGES;}
    public ParsedDocument parse(String text){throw new KnowledgeBaseException(415,"KB_PARSER_UNAVAILABLE","Image parser requires verified file input");}
    @Override public ParsedDocument parse(ParserInput input) {
        try {
            if(Files.size(input.path())>config.maxImageBytes())throw limit();int width,height;
            try(var stream=ImageIO.createImageInputStream(input.path().toFile())) {
                var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new java.io.IOException("Invalid image");var reader=readers.next();
                try{reader.setInput(stream,true,true);width=reader.getWidth(0);height=reader.getHeight(0);}finally{reader.dispose();}
            }
            if(width<=0 || height<=0 || width>10000 || height>10000 || (long)width*height>config.maxImagePixels())throw limit();
            var description=vlm.describe(Files.readAllBytes(input.path()),input.mime(),input.requestId(),input.deadline());
            String text=description.text();if(text.length()>input.maxParsedChars())throw limit();
            var paragraphs=new TextDocumentParser().parse(text);var blocks=new ArrayList<Block>();
            for(var block:paragraphs.blocks())blocks.add(new Block(block.index(),block.blockType(),block.content(),List.of(),
                    Map.of("imageNumber",1,"imageWidth",width,"imageHeight",height,"generated",true,"provenance","VLM_DESCRIPTION_AND_OCR","vlmModel",description.model()),List.of(),List.of()));
            if(blocks.isEmpty())throw new KnowledgeBaseException(502,"KB_VLM_INVALID_RESPONSE","Image description is empty");
            return new ParsedDocument(text,List.copyOf(blocks),"ai-vision-description-ocr-v1");
        }catch(KnowledgeBaseException error){throw error;}
        catch(java.io.IOException error){throw new KnowledgeBaseException(422,"KB_IMAGE_INVALID","Image dimensions or bytes cannot be read");}
    }
    private KnowledgeBaseException limit(){return new KnowledgeBaseException(422,"KB_IMAGE_LIMIT","Image or description exceeds byte/pixel/text limits");}
}
