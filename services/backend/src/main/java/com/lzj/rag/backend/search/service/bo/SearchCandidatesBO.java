package com.lzj.rag.backend.search.service.bo;

import java.util.*;

/**
 * 召回候选及是否触及补充查询上限。
 *
 * @param candidates 本次待领取的任务、可选解析策略或召回候选集合。
 * @param refillLimitReached 召回过滤无效候选后是否达到补充查询次数上限。
 */
public record SearchCandidatesBO(List<SearchCandidateBO> candidates, boolean refillLimitReached) {}
