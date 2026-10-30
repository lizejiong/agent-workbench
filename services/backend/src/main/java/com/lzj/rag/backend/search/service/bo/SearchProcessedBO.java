package com.lzj.rag.backend.search.service.bo;

import java.util.*;

/**
 * 后处理后的证据列表与重排执行状态。
 *
 * @param evidence 从当前有效代次加载的可公开切片证据。
 * @param rerankStatus 重排是否应用、跳过或因失败降级的状态。
 */
public record SearchProcessedBO(List<EvidenceBO> evidence, String rerankStatus) {}
