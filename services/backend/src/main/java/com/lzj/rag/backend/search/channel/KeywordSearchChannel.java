package com.lzj.rag.backend.search.channel;

import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import com.lzj.rag.backend.search.budget.RetrievalDeadline;
import com.lzj.rag.backend.search.repository.EvidenceRepository;
import com.lzj.rag.backend.search.repository.keyword.KeywordIndex;
import com.lzj.rag.backend.search.repository.keyword.dto.KeywordHitDTO;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.search.service.bo.SearchCandidateBO;
import com.lzj.rag.backend.search.service.bo.SearchCandidatesBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/** 关键词召回通道，从索引取候选后以业务数据库过滤失效内容。 */
@Component
public class KeywordSearchChannel implements SearchChannel {

    /** 提供关键词索引写入、查询和历史版本删除的组件。 */
    private final KeywordIndex index;

    /** 从业务库重载当前有效证据及关键词版本的组件。 */
    private final EvidenceRepository records;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param index 提供关键词索引写入、查询和历史版本删除的组件。
     * @param records 从业务库重载当前有效证据及关键词版本的组件。
     */
    public KeywordSearchChannel(KeywordIndex index, EvidenceRepository records) {
        this.index = index;
        this.records = records;
    }

    /**
     * 返回稳定的召回通道名称。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String name() {
        return "KEYWORD";
    }

    /**
     * 从关键词索引召回候选，以业务库过滤失效版本并保留原通道排名。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @param id 当前业务对象的唯一标识。
     * @param limit 当前查询数量、下载字节数或解析字符预算的上限。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public SearchCandidatesBO search(
            KnowledgeBasePrincipal actor,
            SearchBO request,
            String id,
            int limit,
            Instant deadline) {
        RetrievalDeadline.check(deadline);
        // result：当前步骤产生的处理结果。
        var result = new LinkedHashMap<UUID, SearchCandidateBO>();
        // capped：本次召回是否触及补充查询上限。
        boolean capped = false;
        // round：当前召回补充查询的轮次。
        for (int round = 0; round < 2 && Instant.now().isBefore(deadline); round++) {
            // keys：本次查询或索引操作使用的键集合。
            var keys = records.versionKeys(actor, request);
            // hits：当前查询返回的召回命中集合。
            var hits =
                    index.query(
                            request.query().strip(),
                            actor.tenantId(),
                            actor.userId(),
                            keys,
                            Math.min(200, limit * 2),
                            deadline);
            // ids：本次操作涉及的业务对象标识集合。
            var ids = hits.stream().map(KeywordHitDTO::chunkId).toList();
            // live：从业务数据库重载的当前有效证据集合。
            var live = records.hydrate(actor, request, ids);
            // revisions：当前切片对应的可见关键词修订集合。
            var revisions = records.validKeywordRevisions(actor, request, ids);
            // hit：当前向量或关键词召回命中。
            for (var hit : hits) {
                // row：当前数据库记录或表格数据行。
                var row = live.get(hit.chunkId());
                if (row == null
                        || !Objects.equals(hit.syncRevision(), revisions.get(hit.chunkId()))
                        || !EmbeddingCatalog.sha256(row.content()).equals(hit.contentHash()))
                    continue;
                result.putIfAbsent(
                        hit.chunkId(),
                        new SearchCandidateBO(
                                row,
                                name(),
                                hit.rank(),
                                hit.score(),
                                "BM25",
                                hit.syncRevision(),
                                hit.contentHash()));
            }
            if (result.size() >= limit || hits.size() < Math.min(200, limit * 2)) {
                capped = false;
                break;
            }
            capped = true;
        }
        RetrievalDeadline.check(deadline);
        // c：当前关键词召回候选，用切片标识保证排序稳定。
        return new SearchCandidatesBO(
                result.values().stream()
                        .sorted(
                                Comparator.comparingDouble(SearchCandidateBO::rawScore)
                                        .reversed()
                                        .thenComparing(c -> c.evidence().chunkId()))
                        .limit(limit)
                        .toList(),
                capped);
    }
}
