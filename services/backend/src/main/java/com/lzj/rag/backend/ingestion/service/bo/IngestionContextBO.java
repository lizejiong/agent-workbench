package com.lzj.rag.backend.ingestion.service.bo;

import com.lzj.rag.backend.document.service.bo.IngestionSpecBO;
import com.lzj.rag.backend.model.service.bo.EmbeddingBindingBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import java.time.Instant;
import java.util.UUID;

/**
 * 单次入库尝试的不可变输入快照，携带租约凭据、文档版本和模型绑定。
 *
 * @param jobId 持久化入库任务标识。
 * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
 * @param documentId 文档唯一标识。
 * @param kbId 知识库唯一标识。
 * @param actor 本次业务操作的租户及用户身份。
 * @param documentVersion 本次操作固定或返回的文档版本。
 * @param configRevision 业务处理配置修订，用于识别任务输入是否已失效。
 * @param generation 切片或任务所属的数据代次。
 * @param objectKey 对象存储中的源文件键。
 * @param originalFilename 源文件原始名称。
 * @param mimeType 源文件 MIME 类型。
 * @param fileSize 源文件字节数。
 * @param contentHash 源内容或切片正文的 SHA-256 摘要。
 * @param spec 文档入库配置或当前切片策略参数。
 * @param binding 本次业务固定使用的嵌入模型身份与配置快照。
 * @param namespace 向量数据隔离命名空间。
 * @param deadline 本阶段或本次请求必须完成的截止时间。
 * @param attempt 当前执行尝试的序号。
 * @param claimToken 每次领取递增的栅栏令牌，用于拒绝过期工作线程提交。
 * @param leaseOwner 当前租约持有实例的标识。
 */
public record IngestionContextBO(
        UUID jobId,
        String requestId,
        UUID documentId,
        UUID kbId,
        KnowledgeBasePrincipal actor,
        long documentVersion,
        long configRevision,
        long generation,
        String objectKey,
        String originalFilename,
        String mimeType,
        long fileSize,
        String contentHash,
        IngestionSpecBO spec,
        EmbeddingBindingBO binding,
        String namespace,
        Instant deadline,
        int attempt,
        long claimToken,
        String leaseOwner) {}
