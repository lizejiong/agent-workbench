package com.lzj.rag.backend.operations;

import io.micrometer.core.instrument.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Durable global gauges. One bounded, cached read snapshot per scrape interval; never SQL per label. */
@Component
public class KnowledgeBaseMetrics {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate read;
    private volatile Map<String,Double> snapshot=Map.of();
    private volatile long loadedAt;
    private volatile long attemptedAt;
    private volatile boolean readable;
    public KnowledgeBaseMetrics(JdbcTemplate jdbc,PlatformTransactionManager manager,MeterRegistry registry) {
        this.jdbc=jdbc;read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(5);
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        for(String state:List.of("PENDING","RUNNING","FAILED","SUCCEEDED","CANCELLED"))
            Gauge.builder("kb.ingestion.jobs",this,m->m.value("jobs/"+state)).tag("status",state).register(registry);
        for(String kind:List.of("upload","generation","document","remote","keyword"))
            for(String state:List.of("pending","failed"))
                Gauge.builder("kb.cleanup.resources",this,m->m.value("cleanup/"+kind+"/"+state))
                        .tags("kind",kind,"status",state).register(registry);
        for(String state:List.of("PENDING","RUNNING","WAITING_INGESTION","SUCCESS","FAILED","SKIPPED"))
            Gauge.builder("kb.refresh.runs",this,m->m.value("refresh/"+state)).tag("status",state).register(registry);
        for(String state:List.of("PENDING","RUNNING","FAILED","SUCCEEDED"))
            Gauge.builder("kb.keyword.sync",this,m->m.value("keyword/"+state)).tag("status",state).register(registry);
        for(String stage:List.of("FETCH","PARSE","SPLIT","EMBED","PERSIST","PUBLISH"))
            for(String state:List.of("SUCCEEDED","FAILED","CANCELLED"))
                FunctionTimer.builder("kb.ingestion.stage",this,m->m.value("stage/"+stage+"/"+state+"/count").longValue(),
                        m->m.value("stage/"+stage+"/"+state+"/millis"),TimeUnit.MILLISECONDS)
                        .tags("stage",stage,"status",state).register(registry);
        Gauge.builder("kb.metrics.snapshot.available",this,m->m.available()).register(registry);
        Gauge.builder("kb.metrics.snapshot.age",this,m->m.age()).baseUnit("seconds").register(registry);
    }
    private Double value(String key){refresh();return snapshot.getOrDefault(key,0.0);}
    private double available(){refresh();return readable?1:0;}
    private double age(){return loadedAt==0?Double.NaN:TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-loadedAt)/1000.0;}
    public synchronized void refresh() {
        if(attemptedAt!=0 && System.nanoTime()-attemptedAt<TimeUnit.SECONDS.toNanos(10))return;
        attemptedAt=System.nanoTime();
        try {
            Map<String,Double> loaded=read.execute(ignored->{
                var values=new HashMap<String,Double>();
                collect(values,"jobs/","SELECT status,count(*) AS n FROM kb_ingestion_job GROUP BY status");
                collect(values,"refresh/","SELECT status,count(*) AS n FROM kb_refresh_run GROUP BY status");
                collect(values,"keyword/","SELECT status,count(*) AS n FROM kb_keyword_sync GROUP BY status");
                jdbc.query("SELECT stage,status,count(*) AS n,COALESCE(sum(duration_ms),0) AS millis FROM kb_ingestion_stage_log WHERE ended_at IS NOT NULL GROUP BY stage,status",rs->{
                    String key="stage/"+rs.getString("stage")+"/"+rs.getString("status");values.put(key+"/count",rs.getDouble("n"));values.put(key+"/millis",rs.getDouble("millis"));
                });
                jdbc.query("""
                        SELECT kind,CASE WHEN status='FAILED' THEN 'failed' ELSE 'pending' END AS state,count(*) AS n FROM (
                          SELECT 'upload' AS kind,status FROM kb_upload_operation WHERE status IN ('UPLOADING','CLEANUP_PENDING')
                          UNION ALL SELECT 'generation',status FROM kb_generation_cleanup WHERE status<>'SUCCEEDED'
                          UNION ALL SELECT 'document',status FROM kb_document_cleanup WHERE status<>'SUCCEEDED'
                          UNION ALL SELECT 'remote',status FROM kb_remote_object_cleanup WHERE status NOT IN ('RETAINED','SUCCEEDED')
                          UNION ALL SELECT 'keyword',status FROM kb_keyword_cleanup WHERE status<>'SUCCEEDED'
                        ) resources GROUP BY kind,state
                        """,rs->{values.put("cleanup/"+rs.getString("kind")+"/"+rs.getString("state"),rs.getDouble("n"));});
                return Map.copyOf(values);
            });
            snapshot=loaded;loadedAt=System.nanoTime();readable=true;
        } catch(org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException unavailable) {
            readable=false;
            // Last successful values remain; age exposes staleness. Do not log connection strings or SQL/driver errors.
        }
    }
    private void collect(Map<String,Double> values,String prefix,String sql){jdbc.query(sql,rs->{values.put(prefix+rs.getString("status"),rs.getDouble("n"));});}
}
