package com.lzj.rag.backend.ingestion.service.bo;

import com.lzj.rag.backend.chunk.service.bo.ChunkBO;

import java.io.*;
import java.security.*;
import java.util.*;

/**
 * 成功发布后的任务标识、代次、切片和实际 AI 配置修订。
 *
 * @param jobId 持久化入库任务标识。
 * @param generation 切片或任务所属的数据代次。
 * @param chunks 本次处理的有序切片集合。
 * @param aiConfigRevision 本次实际使用或任务固定的 AI 配置修订摘要。
 */
public record IngestionResultBO(
        UUID jobId, long generation, List<ChunkBO> chunks, String aiConfigRevision) {}
