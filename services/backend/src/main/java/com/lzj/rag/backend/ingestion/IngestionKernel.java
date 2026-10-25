package com.lzj.rag.backend.ingestion;

import com.lzj.rag.backend.chunk.*;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.model.*;
import com.lzj.rag.backend.parser.*;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.backend.storage.*;
import org.springframework.stereotype.Service;
import java.io.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Pipeline runs outside the claim transaction; publication is a separate short transaction. */
@Service
public class IngestionKernel {
    public record Result(UUID jobId,long generation,List<Chunk> chunks,String aiConfigRevision) { }
    private record Source(java.nio.file.Path path,String mime) implements AutoCloseable {
        public void close(){remove(path);}
    }
    private final IngestionJobService jobs;private final ParserRegistry parsers;private final ChunkingService chunking;
    private final EmbeddingClient embeddings;private final ChunkIndexWriter writer;private final ObjectStorageClient storage;
    private final IngestionProperties limits;private final StorageProperties storageLimits;
    public IngestionKernel(IngestionJobService jobs,ParserRegistry parsers,ChunkingService chunking,EmbeddingClient embeddings,
            ChunkIndexWriter writer,ObjectStorageClient storage,IngestionProperties limits,StorageProperties storageLimits){
        this.jobs=jobs;this.parsers=parsers;this.chunking=chunking;this.embeddings=embeddings;this.writer=writer;this.storage=storage;this.limits=limits;this.storageLimits=storageLimits;
    }
    public Result ingest(KnowledgeBasePrincipal actor,UUID documentId) {
        return execute(jobs.start(actor,documentId));
    }
    public Result execute(IngestionContext context) {
        // A duplicate dispatch must not fail or alter the legitimate execution.
        jobs.enter(context);
        try {
            jobs.begin(context,"FETCH",1,"objects");ParsedDocument parsed;
            try(var source=read(context)) {
                jobs.end(context,"FETCH",context.fileSize(),"bytes");deadline(context);jobs.begin(context,"PARSE",context.fileSize(),"bytes");
                parsed=parsers.resolve(source.mime(),context.spec().parser(),context.originalFilename(),context.spec().parseProfile())
                        .parse(new ParserInput(source.path(),context.originalFilename(),source.mime(),limits.maxParsedChars(),context.requestId(),context.deadline()));
                jobs.end(context,"PARSE",parsed.blocks().size(),"blocks");
            }
            deadline(context);jobs.begin(context,"SPLIT",parsed.blocks().size(),"blocks");
            var chunks=chunking.chunk(context.documentId(),context.generation(),parsed,context.spec().splitter());jobs.end(context,"SPLIT",chunks.size(),"chunks");
            deadline(context);jobs.begin(context,"EMBED",chunks.size(),"texts");
            var result=embeddings.embed(context.binding(),chunks.stream().map(Chunk::embeddingText).toList(),context.requestId(),context.deadline());
            jobs.pinAiRevision(context,result.aiConfigRevision());jobs.end(context,"EMBED",result.vectors().size(),"vectors");
            deadline(context);jobs.begin(context,"PERSIST",chunks.size(),"chunks");writer.writeAndPublish(context,chunks,result);
            com.lzj.rag.backend.operations.OperationLog.committed(context.kbId(),context.documentId(),context.jobId(),context.generation(),"COMPLETE","SUCCEEDED");
            return new Result(context.jobId(),context.generation(),chunks,result.aiConfigRevision());
        } catch(RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("kbId={} documentId={} jobId={} generation={} stage=EXECUTION errorCode={}",
                    context.kbId(),context.documentId(),context.jobId(),context.generation(),error instanceof KnowledgeBaseException e?e.code():"KB_INGESTION_FAILED");
            try{jobs.fail(context,error);}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Ingestion failure state awaits recovery jobId={}",context.jobId());}
            throw error;
        }
    }
    private Source read(IngestionContext context) {
        java.nio.file.Path spool=null;
        try {
            var directory=storageLimits.tempDirectory();
            if(directory==null || directory.toString().isBlank())directory=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
            java.nio.file.Files.createDirectories(directory);
            spool=java.nio.file.Files.createTempFile(directory,"kb-source-"+context.jobId()+"-"+context.claimToken()+"-",".tmp");
            var digest=MessageDigest.getInstance("SHA-256");long count=0;
            try(var input=storage.open(context.objectKey());var output=java.nio.file.Files.newOutputStream(spool)) {
                byte[] buffer=new byte[8192];int n;
                while((n=input.read(buffer))!=-1){deadline(context);count+=n;
                    if(count>storageLimits.maxUploadBytes() || count>context.fileSize())throw new KnowledgeBaseException(422,"KB_SOURCE_TOO_LARGE","Stored source exceeds bounded file budget");
                    digest.update(buffer,0,n);output.write(buffer,0,n);
                }
            }
            if(count!=context.fileSize() || !HexFormat.of().formatHex(digest.digest()).equals(context.contentHash()))
                throw new KnowledgeBaseException(409,"KB_SOURCE_CHANGED","Stored object bytes differ from upload metadata");
            String mime=SupportedFiles.detect(spool);
            if(!mime.equals(context.mimeType()))throw new KnowledgeBaseException(409,"KB_SOURCE_CHANGED","Stored object MIME differs from upload metadata");
            SupportedFiles.compatible(context.originalFilename(),mime);return new Source(spool,mime);
        }catch(RuntimeException error){remove(spool);throw error;}
        catch(IOException | NoSuchAlgorithmException error){remove(spool);throw new KnowledgeBaseException(503,"KB_STORAGE_UNAVAILABLE","Stored source staging or download failed temporarily");}
    }
    private static void remove(java.nio.file.Path path){if(path!=null)try{java.nio.file.Files.deleteIfExists(path);}catch(IOException e){org.slf4j.LoggerFactory.getLogger(IngestionKernel.class).warn("Source spool awaits bounded cleanup sweep");}}
    private static void deadline(IngestionContext context){
        if(Thread.currentThread().isInterrupted())throw new KnowledgeBaseException(409,"KB_JOB_INTERRUPTED","Ingestion execution interrupted");
        if(!Instant.now().isBefore(context.deadline()))throw new KnowledgeBaseException(504,"KB_INGESTION_TIMEOUT","Ingestion attempt deadline exceeded");
    }
}
