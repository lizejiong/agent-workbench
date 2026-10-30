package com.lzj.rag.backend.document.controller.vo;

/**
 * 文档处理配置，版本化记录解析器、解析档位及切片策略。
 *
 * @param schemaVersion 文档处理配置的结构版本。
 * @param parser 当前选择的解析策略或解析器标识。
 * @param splitter 文档选择的切片策略及容量配置。
 * @param parseProfile 本次解析使用的快速或高保真档位。
 */
public record IngestionSpecVO(
        Integer schemaVersion, String parser, SplitterVO splitter, String parseProfile) {}
