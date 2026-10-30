package com.lzj.rag.backend.ingestion.controller.vo;

import java.time.*;
import java.util.*;

/**
 * 单次尝试内某个处理阶段的耗时、输入输出和错误状态。
 *
 * @param stage 当前流水线阶段标识。
 * @param attempt 当前执行尝试的序号。
 * @param status 当前业务操作或任务状态。
 * @param startedAt 任务或阶段首次开始执行的时间。
 * @param endedAt 本阶段结束时间。
 * @param durationMs 本次运行或阶段耗时，单位为毫秒。
 * @param inputCount 阶段处理的输入数量。
 * @param inputUnit 阶段输入计数的计量单位。
 * @param outputCount 阶段产生的输出数量。
 * @param outputUnit 阶段输出计数的计量单位。
 * @param errorCode 可供调用方和运维识别的稳定错误码。
 * @param errorMessage 对外展示的错误说明，不包含内部连接或源文件内容。
 */
public record IngestionStageVO(
        String stage,
        int attempt,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        Long durationMs,
        Long inputCount,
        String inputUnit,
        Long outputCount,
        String outputUnit,
        String errorCode,
        String errorMessage) {}
