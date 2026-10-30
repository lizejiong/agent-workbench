package com.lzj.rag.ai.text.controller;

import com.lzj.rag.ai.text.controller.request.TextRequest;
import com.lzj.rag.ai.text.controller.vo.TextVO;
import com.lzj.rag.ai.text.controller.vo.TokenEstimateVO;
import com.lzj.rag.ai.text.converter.TextWebConverter;
import com.lzj.rag.ai.text.service.TextService;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.context.ReactiveRequestIdContext;
import com.lzj.rag.framework.starter.web.result.Results;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/** 文本工具 HTTP 入口，调用业务服务后使用公共响应契约返回。 */
@RestController
@RequestMapping("/ai/utils")
public class TextController {

    /** 当前 Controller 所调用的业务服务契约。 */
    private final TextService service;

    /**
     * 注入可由其他入口复用的文本业务服务。
     *
     * @param service 当前 Controller 所调用的业务服务契约。
     */
    public TextController(TextService service) {
        this.service = service;
    }

    /**
     * 校验文本输入后调用 Token 估算业务，并转换为统一响应。
     *
     * @param request 当前操作的输入请求数据。
     * @param exchange 当前 WebFlux 请求上下文，公共过滤器已写入请求标识。
     * @return 携带请求 ID 的估算响应。
     */
    @PostMapping("/estimate-tokens")
    public Result<TokenEstimateVO> estimate(
            @Valid @RequestBody TextRequest request, ServerWebExchange exchange) {
        return result(TextWebConverter.toVO(service.estimateTokens(request.text())), exchange);
    }

    /**
     * 校验文本输入后移除完整围栏，并转换为统一响应。
     *
     * @param request 当前操作的输入请求数据。
     * @param exchange 当前 WebFlux 请求上下文，公共过滤器已写入请求标识。
     * @return 携带请求 ID 的文本响应。
     */
    @PostMapping("/strip-code-fence")
    public Result<TextVO> strip(
            @Valid @RequestBody TextRequest request, ServerWebExchange exchange) {
        return result(TextWebConverter.toVO(service.stripCodeFence(request.text())), exchange);
    }

    /**
     * 通过公共 Results 显式传入响应式请求 ID。
     *
     * @param data 业务层已经完成处理、等待转换为响应的数据。
     * @param exchange 当前 WebFlux 请求上下文，公共过滤器已写入请求标识。
     * @param <T> 当前业务结果或输入元素的类型。
     * @return 符合公共契约的成功响应。
     */
    private <T> Result<T> result(T data, ServerWebExchange exchange) {
        return Results.success(data, ReactiveRequestIdContext.get(exchange));
    }
}
