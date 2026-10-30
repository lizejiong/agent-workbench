package com.lzj.rag.ai.model.controller;

import com.lzj.rag.ai.model.controller.request.ChatRequest;
import com.lzj.rag.ai.model.controller.request.EmbedRequest;
import com.lzj.rag.ai.model.controller.request.RerankRequest;
import com.lzj.rag.ai.model.controller.vo.*;
import com.lzj.rag.ai.model.converter.ModelWebConverter;
import com.lzj.rag.ai.model.service.ModelService;
import com.lzj.rag.ai.model.service.bo.ModelEventBO;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.context.ReactiveRequestIdContext;
import com.lzj.rag.framework.starter.web.result.Results;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 模型 HTTP 入口，负责接参、Web 对象转换和 SSE 封装。 */
@RestController
@RequestMapping("/ai")
public class ModelController {

    /** 当前 Controller 所调用的业务服务契约。 */
    private final ModelService service;

    /**
     * 注入模型业务契约，HTTP 层不直接操作注册器或供应商客户端。
     *
     * @param service 当前 Controller 所调用的业务服务契约。
     */
    public ModelController(ModelService service) {
        this.service = service;
    }

    /**
     * 接收模型别名和可选维度，返回可公开的嵌入模型描述。
     *
     * @param alias 应用内部的逻辑模型别名，不是供应商原生模型名。
     * @param dimensions 本次向量结果使用的维度。
     * @param exchange 当前 WebFlux 请求上下文，公共过滤器已写入请求标识。
     * @return 使用公共响应契约包装的模型元数据。
     */
    @GetMapping("/embedding-models/{alias}")
    public Result<EmbeddingModelVO> embeddingModel(
            @PathVariable String alias,
            @RequestParam(required = false) Integer dimensions,
            ServerWebExchange exchange) {
        return result(ModelWebConverter.toVO(service.embeddingModel(alias, dimensions)), exchange);
    }

    /**
     * 处理普通对话请求，将 Request 转为 BO，再把业务结果转为 VO。
     *
     * @param request 当前操作的输入请求数据。
     * @param exchange 当前 WebFlux 请求上下文，公共过滤器已写入请求标识。
     * @return 包含对话响应对象的异步统一响应。
     */
    @PostMapping("/chat")
    public Mono<Result<ChatVO>> chat(
            @Valid @RequestBody ChatRequest request, ServerWebExchange exchange) {
        // data：业务层已经完成处理、等待转换为响应的数据。
        return service.chat(ModelWebConverter.toBO(request), false, id(exchange))
                .map(data -> result(ModelWebConverter.toVO(data), exchange));
    }

    /**
     * 处理流式对话请求，只在 Web 边界把业务事件封装为 SSE。
     *
     * @param request 当前操作的输入请求数据。
     * @param exchange 当前 WebFlux 请求上下文，公共过滤器已写入请求标识。
     * @return 对话 SSE 事件流。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ModelEventVO>> stream(
            @Valid @RequestBody ChatRequest request, ServerWebExchange exchange) {
        return service.stream(ModelWebConverter.toBO(request), false, id(exchange))
                .map(this::event);
    }

    /**
     * 使用视觉模式执行与对话共用的业务流程。
     *
     * @param request 当前操作的输入请求数据。
     * @param exchange 当前 WebFlux 请求上下文，公共过滤器已写入请求标识。
     * @return 包含视觉理解结果的异步统一响应。
     */
    @PostMapping("/vision")
    public Mono<Result<ChatVO>> vision(
            @Valid @RequestBody ChatRequest request, ServerWebExchange exchange) {
        // data：业务层已经完成处理、等待转换为响应的数据。
        return service.chat(ModelWebConverter.toBO(request), true, id(exchange))
                .map(data -> result(ModelWebConverter.toVO(data), exchange));
    }

    /**
     * 使用视觉模式输出模型事件。
     *
     * @param request 当前操作的输入请求数据。
     * @param exchange 当前 WebFlux 请求上下文，公共过滤器已写入请求标识。
     * @return 视觉理解 SSE 事件流。
     */
    @PostMapping(value = "/vision/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ModelEventVO>> visionStream(
            @Valid @RequestBody ChatRequest request, ServerWebExchange exchange) {
        return service.stream(ModelWebConverter.toBO(request), true, id(exchange)).map(this::event);
    }

    /**
     * 接收向量请求，并把完整业务结果映射为向量响应。
     *
     * @param request 当前操作的输入请求数据。
     * @param exchange 当前 WebFlux 请求上下文，公共过滤器已写入请求标识。
     * @return 包含向量与模型身份的异步统一响应。
     */
    @PostMapping("/embed")
    public Mono<Result<EmbedVO>> embed(
            @Valid @RequestBody EmbedRequest request, ServerWebExchange exchange) {
        // data：业务层已经完成处理、等待转换为响应的数据。
        return service.embed(ModelWebConverter.toBO(request), id(exchange))
                .map(data -> result(ModelWebConverter.toVO(data), exchange));
    }

    /**
     * 接收重排请求，并映射业务排序结果。
     *
     * @param request 当前操作的输入请求数据。
     * @param exchange 当前 WebFlux 请求上下文，公共过滤器已写入请求标识。
     * @return 包含重排结果的异步统一响应。
     */
    @PostMapping("/rerank")
    public Mono<Result<RerankVO>> rerank(
            @Valid @RequestBody RerankRequest request, ServerWebExchange exchange) {
        // data：业务层已经完成处理、等待转换为响应的数据。
        return service.rerank(ModelWebConverter.toBO(request), id(exchange))
                .map(data -> result(ModelWebConverter.toVO(data), exchange));
    }

    /**
     * 将业务事件类型写入 SSE event 字段，将转换后的对象写入 data。
     *
     * @param event 当前收到或即将输出的模型流事件。
     * @return 单个 SSE 事件。
     */
    private ServerSentEvent<ModelEventVO> event(ModelEventBO event) {
        return ServerSentEvent.builder(ModelWebConverter.toVO(event)).event(event.type()).build();
    }

    /**
     * 读取公共 WebFlux 过滤器写入的请求标识。
     *
     * @param exchange 当前 WebFlux 请求上下文，公共过滤器已写入请求标识。
     * @return 当前请求的关联 ID。
     */
    private static String id(ServerWebExchange exchange) {
        return ReactiveRequestIdContext.get(exchange);
    }

    /**
     * 调用公共响应构造器，并显式传入请求标识以适配响应式线程切换。
     *
     * @param data 业务层已经完成处理、等待转换为响应的数据。
     * @param exchange 当前 WebFlux 请求上下文，公共过滤器已写入请求标识。
     * @param <T> 当前业务结果或输入元素的类型。
     * @return 包含数据和同一请求 ID 的成功响应。
     */
    private <T> Result<T> result(T data, ServerWebExchange exchange) {
        return Results.success(data, id(exchange));
    }
}
