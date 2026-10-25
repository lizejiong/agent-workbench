package com.lzj.rag.backend.refresh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.document.*;
import com.lzj.rag.backend.ingestion.IngestionJobService;
import com.lzj.rag.backend.knowledgebase.*;
import com.lzj.rag.backend.model.EmbeddingCatalog;
import com.lzj.rag.backend.parser.SupportedFiles;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.lzj.rag.backend.refresh.RemoteTypes.*;

@Service
public class UrlImportService {
    private final JdbcTemplate jdbc;private final KnowledgeBaseService bases;private final DocumentService documents;
    private final IngestionJobService jobs;private final RefreshState state;private final RemoteDownloader downloader;private final ObjectMapper json;
    private final TransactionTemplate tx;
    public UrlImportService(JdbcTemplate jdbc,KnowledgeBaseService bases,DocumentService documents,IngestionJobService jobs,RefreshState state,
            RemoteDownloader downloader,ObjectMapper json,PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.bases=bases;this.documents=documents;this.jobs=jobs;this.state=state;this.downloader=downloader;this.json=json;
        tx=new TransactionTemplate(transactions);tx.setTimeout(10);
    }
    public Imported importUrl(KnowledgeBasePrincipal actor,UUID kb,String key,ImportRequest request) {
        bases.requireOwned(actor,kb);
        if(key==null || !key.matches("[A-Za-z0-9_.:-]{16,128}"))throw new KnowledgeBaseException(400,"KB_INVALID_INPUT","Idempotency-Key must contain 16..128 safe ASCII characters");
        String url=downloader.validateUrl(request.url());validateFilename(request.filename());
        String payload=EmbeddingCatalog.sha256(encode(List.of(url,request.filename(),Objects.toString(request.name(),""),request.ingestionSpec(),request.ingest())));
        var operation=tx.execute(status->{
            bases.lockOwned(actor,kb);var rows=jdbc.queryForList("SELECT *,lease_until>clock_timestamp() AS valid FROM kb_url_import WHERE kb_id=? AND tenant_id=? AND owner_id=? AND request_id=? FOR UPDATE",kb,actor.tenantId(),actor.userId(),key);
            if(!rows.isEmpty()) {
                var old=rows.get(0);if(!payload.equals(old.get("payload_hash")))throw new KnowledgeBaseException(409,"KB_IDEMPOTENCY_CONFLICT","Import key is bound to a different request");
                if("RUNNING".equals(old.get("status")) && !Boolean.TRUE.equals(old.get("valid"))){jdbc.update("UPDATE kb_url_import SET status='FAILED',error_code='KB_IMPORT_EXPIRED' WHERE id=?",old.get("id"));old.put("status","FAILED");}
                return old;
            }
            UUID id=UUID.randomUUID();jdbc.update("INSERT INTO kb_url_import(id,kb_id,tenant_id,owner_id,request_id,payload_hash,status,lease_until) VALUES (?,?,?,?,?,?,'RUNNING',clock_timestamp()+interval '10 minutes')",id,kb,actor.tenantId(),actor.userId(),key,payload);
            return new HashMap<String,Object>(Map.of("id",id,"status","NEW"));
        });
        if("COMPLETED".equals(operation.get("status")))return new Imported(documents.get(actor,(UUID)operation.get("document_id")),(UUID)operation.get("job_id"),true);
        if(!"NEW".equals(operation.get("status")))throw new KnowledgeBaseException(409,"KB_IMPORT_NOT_READY","Import is running or previously failed; inspect document/storage operations and use a new key after recovery");
        UUID id=(UUID)operation.get("id");
        try(var download=downloader.download(url,null,null,null)) {
            var metadata=new DocumentTypes.UploadMetadata(request.name(),request.ingestionSpec());
            var uploaded=documents.uploadSource(actor,kb,download.file(request.filename()),encode(metadata),"url-"+id,url,document->{
                if(jdbc.update("UPDATE kb_url_import SET status='COMPLETED',document_id=?,object_key=?,etag=?,last_modified=? WHERE id=? AND status='RUNNING' AND lease_until>clock_timestamp()",document.id(),document.objectKey(),download.etag(),download.lastModified(),id)!=1)
                    throw new KnowledgeBaseException(409,"KB_IMPORT_EXPIRED","Import no longer owns its reservation");
                state.ensure(document.id());
                if(request.ingest()) {
                    UUID job=jobs.submit(actor,document.id(),"import-"+id,null).job().id();
                    jdbc.update("UPDATE kb_url_import SET job_id=? WHERE id=?",job,id);
                }
            });
            UUID job=jdbc.queryForObject("SELECT job_id FROM kb_url_import WHERE id=?",UUID.class,id);
            return new Imported(documents.get(actor,uploaded.document().id()),job,false);
        }catch(RuntimeException error) {
            jdbc.update("UPDATE kb_url_import SET status='FAILED',error_code=? WHERE id=? AND status='RUNNING'",error instanceof KnowledgeBaseException kbError?kbError.code():"KB_IMPORT_FAILED",id);
            throw error;
        }
    }
    public static void validateFilename(String name) {
        if(name==null || name.isBlank() || name.length()>255 || !name.equals(name.strip()) || name.contains("/") || name.contains("\\") || name.codePoints().anyMatch(Character::isISOControl))
            throw new KnowledgeBaseException(400,"KB_INVALID_INPUT","Provide a safe filename with a supported extension");
        SupportedFiles.extensionAllowed(name);
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
