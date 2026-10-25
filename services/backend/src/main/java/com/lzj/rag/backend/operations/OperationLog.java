package com.lzj.rag.backend.operations;

import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.*;
import java.util.UUID;

/** Only stable identifiers, bounded state names and counts; no source, user input or exception messages. */
public final class OperationLog {
    private OperationLog(){ }
    public static void committed(UUID kb,UUID document,UUID job,Long generation,String stage,String status) {
        Runnable write=()->LoggerFactory.getLogger("knowledge-base.operations").info(
                "kbId={} documentId={} jobId={} generation={} stage={} status={}",kb,document,job,generation,stage,status);
        if(TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){write.run();}});
        else write.run();
    }
}
