package com.lzj.rag.backend.knowledgebase;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {
    // Bound pagination parameters avoid SQL concatenation and an unrelated SQL parser dependency.
    @Select("""
        SELECT * FROM kb_knowledge_base
        WHERE tenant_id = #{tenant} AND owner_id = #{owner} AND deleted_at IS NULL
          AND (#{name} = '' OR strpos(lower(name), lower(#{name})) > 0)
        ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}
        """)
    List<KnowledgeBase> selectOwnedPage(@Param("tenant") String tenant, @Param("owner") String owner,
                                      @Param("size") int size, @Param("offset") long offset, @Param("name") String name);
}
