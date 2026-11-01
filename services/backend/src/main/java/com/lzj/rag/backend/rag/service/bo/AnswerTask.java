package com.lzj.rag.backend.rag.service.bo;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.service.StreamChatClient;
import com.lzj.rag.backend.rag.service.AnswerEvents;
import com.lzj.rag.backend.search.budget.RetrievalDeadline;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import java.time.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** 任务必须实际开始并经过 finally；不取消尚未执行的 Future 而丢失结算。 */
public final class AnswerTask implements StreamChatClient.Cancellation {
    public final KnowledgeBasePrincipal actor;
    public final AnswerRunBO run;
    public final AnswerEvents events;
    public final CompletableFuture<RagAnswerBO> completion=new CompletableFuture<>();
    private final StringBuilder partial=new StringBuilder();
    private final AtomicReference<String> stopped=new AtomicReference<>();
    private final AtomicReference<Runnable> downstream=new AtomicReference<>();
    private final long endNanos;
    private volatile Thread worker;
    private final java.util.function.Consumer<RagAnswerBO> recorder;
    private volatile RagAnswerBO draft;

    public AnswerTask(KnowledgeBasePrincipal actor,AnswerRunBO run,AnswerEvents events,java.util.function.Consumer<RagAnswerBO> recorder) {
        this.actor=actor;this.run=run;this.events=events;
        this.recorder=recorder;
        endNanos=System.nanoTime()+Math.max(0,Duration.between(Instant.now(),run.deadline()).toNanos());
    }
    public void attach(){worker=Thread.currentThread();check();}
    public void detach(){worker=null;Thread.interrupted();}
    public void stop(String reason) {
        if(completion.isDone())return;
        stopped.compareAndSet(null,reason);
        Runnable cancel=downstream.get();if(cancel!=null)cancel.run();
        Thread thread=worker;if(thread!=null)thread.interrupt();
    }
    public String reason(){return stopped.get();}
    public long remainingNanos(){return Math.max(1,endNanos-System.nanoTime());}
    public boolean expired(){return System.nanoTime()>=endNanos || !Instant.now().isBefore(run.deadline());}
    public void check() {
        if(expired())stopped.compareAndSet(null,"TIMED_OUT");
        if(stopped.get()!=null)throw new KnowledgeBaseException(stopped.get().equals("TIMED_OUT")?504:503,
                stopped.get().equals("TIMED_OUT")?"RAG_ANSWER_TIMEOUT":"RAG_ANSWER_CANCELLED","Answer execution stopped");
        RetrievalDeadline.check(run.deadline());
    }
    public void register(Runnable cancel){downstream.set(cancel);if(stopped.get()!=null || Thread.currentThread().isInterrupted())cancel.run();check();}
    public void unregister(Runnable cancel){downstream.compareAndSet(cancel,null);}
    public void checkpoint(RagAnswerBO snapshot){check();draft=snapshot;recorder.accept(snapshot);}
    public void startedModel(String model) {
        var d=draft;if(d==null || d.modelCalls().isEmpty())return;
        var requested=d.modelCalls().get(0);
        checkpoint(new RagAnswerBO(d.answerId(),d.conversationId(),d.requestId(),d.status(),partial(),d.citations(),d.sources(),d.degraded(),
                d.citationValidation(),null,java.util.List.of(new RagAnswerBO.ModelCallBO("ANSWER_STARTED",model,requested.promptVersion(),requested.promptHash(),null)),
                d.query(),d.retrievalTrace(),d.evidenceContextHash(),false,null));
    }
    public synchronized String partial(){return partial.toString();}
    public synchronized void replace(String text){partial.setLength(0);partial.append(text);}
    public void append(String text,int maxChars){check();synchronized(this){if((long)partial.length()+text.length()>maxChars)
        throw new KnowledgeBaseException(502,"RAG_MODEL_OUTPUT_BUDGET","Model output exceeds the answer limit");partial.append(text);}events.message(text);}
}
