package com.lzj.rag.backend.knowledgebase.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lzj.rag.backend.knowledgebase.dao.entity.KnowledgeBaseDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 知识库数据访问接口，分页查询始终限定租户和所有者。 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseDO> {

    /**
     * 在租户及用户范围内分页读取知识库，同时返回关联文档计数。
     *
     * @param tenant 数据所属租户。
     * @param owner 数据所有者或当前租约持有实例的标识。
     * @param size 分页容量或当前数据的字节数。
     * @param offset 分页偏移量或数据库时间的带偏移表示。
     * @param name 当前业务资源或策略的公开名称。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Select(
            """
        SELECT * FROM kb_knowledge_base
        WHERE tenant_id = #{tenant} AND owner_id = #{owner} AND deleted_at IS NULL
          AND (#{name} = '' OR strpos(lower(name), lower(#{name})) > 0)
        ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}
        """)
    List<KnowledgeBaseDO> selectOwnedPage(
            @Param("tenant") String tenant,
            @Param("owner") String owner,
            @Param("size") int size,
            @Param("offset") long offset,
            @Param("name") String name);
}
