package com.lzj.rag.backend.ingestion.service.bo;

import java.time.*;
import java.util.*;

/**
 * 提交任务的结果，标记是否复用了相同幂等键的既有任务。
 *
 * @param job 当前入库任务记录或任务标识。
 * @param replayed 是否复用了相同幂等键的已有结果。
 */
public record IngestionSubmissionBO(IngestionJobBO job, boolean replayed) {}
