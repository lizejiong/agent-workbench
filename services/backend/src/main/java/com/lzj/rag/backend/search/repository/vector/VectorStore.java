package com.lzj.rag.backend.search.repository.vector;

import com.lzj.rag.backend.search.repository.vector.dto.VectorHitDTO;
import com.lzj.rag.backend.search.repository.vector.dto.VectorRecordDTO;
import com.lzj.rag.backend.search.repository.vector.dto.VectorScopeDTO;

import java.util.*;

/** 向量存储契约，以租户、所有者、知识库和向量命名空间约束读写范围。 */
public interface VectorStore {

    /**
     * 在固定权限和命名空间内分批写入切片向量及其元数据。
     *
     * @param scope 限制所有权、知识库和向量命名空间的访问范围。
     * @param records 当前存储记录集合或证据重载组件。
     */
    void write(VectorScopeDTO scope, List<VectorRecordDTO> records);

    /**
     * 在授权范围和有效代次内按余弦距离召回有限数量的切片。
     *
     * @param scope 限制所有权、知识库和向量命名空间的访问范围。
     * @param query 与知识库固定向量空间一致的查询向量。
     * @param limit 当前查询数量、下载字节数或解析字符预算的上限。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    List<VectorHitDTO> query(VectorScopeDTO scope, float[] query, int limit);

    /**
     * 在授权范围和有效代次内按余弦距离召回有限数量的切片。
     *
     * @param scopes 本次向量检索允许访问的范围集合。
     * @param query 与知识库固定向量空间一致的查询向量。
     * @param aiConfigRevision 本次实际使用或任务固定的 AI 配置修订摘要。
     * @param documentIds 限定检索范围的文档标识集合。
     * @param limit 当前查询数量、下载字节数或解析字符预算的上限。
     * @param minScore 仅适用于向量通道的最低相似度阈值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    List<VectorHitDTO> query(
            List<VectorScopeDTO> scopes,
            float[] query,
            String aiConfigRevision,
            List<UUID> documentIds,
            int limit,
            Double minScore);

    /**
     * 删除指定文档及代次范围内的向量数据。
     *
     * @param scope 限制所有权、知识库和向量命名空间的访问范围。
     * @param documentId 文档唯一标识。
     * @param generation 切片或任务所属的数据代次。
     */
    void deleteByDocument(VectorScopeDTO scope, UUID documentId, long generation);

    /**
     * 删除指定权限范围内单个切片的向量数据。
     *
     * @param scope 限制所有权、知识库和向量命名空间的访问范围。
     * @param chunkId 切片唯一标识。
     */
    void deleteByChunk(VectorScopeDTO scope, UUID chunkId);
}
