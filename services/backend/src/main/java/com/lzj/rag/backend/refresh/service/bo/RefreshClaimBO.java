package com.lzj.rag.backend.refresh.service.bo;

import org.springframework.transaction.*;

import java.time.*;
import java.util.*;

/**
 * 刷新尝试的运行标识及租约栅栏凭据。
 *
 * @param id 当前业务对象的唯一标识。
 * @param documentId 文档唯一标识。
 * @param kbId 知识库唯一标识。
 * @param token 当前任务的租约栅栏令牌或清理凭据。
 * @param owner 数据所有者或当前租约持有实例的标识。
 */
public record RefreshClaimBO(UUID id, UUID documentId, UUID kbId, long token, String owner) {}
