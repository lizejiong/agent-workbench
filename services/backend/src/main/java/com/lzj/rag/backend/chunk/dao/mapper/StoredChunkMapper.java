package com.lzj.rag.backend.chunk.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lzj.rag.backend.chunk.dao.entity.ChunkDO;

import org.apache.ibatis.annotations.Mapper;

/** 切片实体的数据库访问接口，复用公共 MyBatis Plus 能力。 */
@Mapper
public interface StoredChunkMapper extends BaseMapper<ChunkDO> {}
