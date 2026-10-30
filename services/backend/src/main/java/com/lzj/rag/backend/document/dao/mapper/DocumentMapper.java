package com.lzj.rag.backend.document.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lzj.rag.backend.document.dao.entity.DocumentDO;

import org.apache.ibatis.annotations.Mapper;

/** 文档实体的数据库访问接口。 */
@Mapper
public interface DocumentMapper extends BaseMapper<DocumentDO> {}
