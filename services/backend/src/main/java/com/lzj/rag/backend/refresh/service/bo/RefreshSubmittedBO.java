package com.lzj.rag.backend.refresh.service.bo;

/**
 * 刷新提交结果及幂等重放标记。
 *
 * @param run 当前刷新运行记录或运行标识。
 * @param replayed 是否复用了相同幂等键的已有结果。
 */
public record RefreshSubmittedBO(RefreshRunBO run, boolean replayed) {}
