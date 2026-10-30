package com.lzj.rag.ai.infra.provider.dto;

/**
 * 供应商工具调用或工具增量；增量帧允许部分字段缺失。
 *
 * @param id 供应商工具调用 ID，用于关联后续工具结果。
 * @param name 工具函数名称或当前具名配置的标识。
 * @param arguments 工具调用参数的 JSON 字符串；流式场景可能只是一个增量片段。
 * @param index 同一轮工具调用的零起始位置，增量根据此值累加。
 */
public record ProviderToolCallDTO(String id, String name, String arguments, Integer index) {}
