package com.lzj.rag.backend.operations.logging;

import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.*;

import java.util.UUID;

/** 在事务提交后记录业务完成日志，避免将回滚操作报告为成功。 */
public final class OperationLog {

    /** 禁止实例化仅包含类型定义或静态方法的工具类。 */
    private OperationLog() {}

    /**
     * 有事务时登记提交回调，无事务时立即记录已完成的业务操作。
     *
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param document 本次操作涉及的文档唯一标识。
     * @param job 当前入库任务记录或任务标识。
     * @param generation 切片或任务所属的数据代次。
     * @param stage 当前流水线阶段标识。
     * @param status 当前业务操作或任务状态。
     */
    public static void committed(
            UUID kb, UUID document, UUID job, Long generation, String stage, String status) {
        // write：需要在写事务内执行的状态变更回调。
        Runnable write =
                () ->
                        LoggerFactory.getLogger("knowledge-base.operations")
                                .info(
                                        "kbId={} documentId={} jobId={} generation={} stage={} status={}",
                                        kb,
                                        document,
                                        job,
                                        generation,
                                        stage,
                                        status);
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {

                        /** 仅在事务成功提交后输出本次操作完成日志。 */
                        @Override
                        public void afterCommit() {
                            write.run();
                        }
                    });
        else write.run();
    }
}
