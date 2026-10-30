package com.lzj.rag.backend.search.repository.keyword;

import com.lzj.rag.backend.search.repository.keyword.dto.KeywordChunkDTO;
import com.lzj.rag.backend.search.repository.keyword.dto.KeywordHitDTO;

import java.time.Instant;
import java.util.*;

/** 关键词索引契约，写入和查询都携带明确的索引版本或截止时间。 */
public interface KeywordIndex {

    /** 检查关键词索引及其分析器、字段映射是否可用。 */
    void ready();

    /**
     * 在给定截止时间内批量写入关键词切片。
     *
     * @param chunks 本次处理的有序切片集合。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     */
    void write(List<KeywordChunkDTO> chunks, Instant deadline);

    /**
     * 在固定版本及权限范围内查询关键词候选。
     *
     * @param query 检索查询文本或已生成的查询向量。
     * @param tenant 数据所属租户。
     * @param owner 数据所有者或当前租约持有实例的标识。
     * @param versionKeys 当前范围可见的关键词索引版本键集合。
     * @param limit 当前查询数量、下载字节数或解析字符预算的上限。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    List<KeywordHitDTO> query(
            String query,
            String tenant,
            String owner,
            List<String> versionKeys,
            int limit,
            Instant deadline);

    /**
     * 删除指定知识库的过期同步版本数据。
     *
     * @param document 本次操作涉及的文档唯一标识。
     * @param revision 当前同步数据或配置使用的修订标识。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     */
    void deleteBefore(UUID document, long revision, Instant deadline);
}
