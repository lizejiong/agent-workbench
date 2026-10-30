package com.lzj.rag.backend.document.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 收紧 JSON 字符串字段的反序列化规则，避免将其他类型静默转换为文本。 */
@Configuration(proxyBeanMethods = false)
public class StrictJsonConfiguration {

    /**
     * 禁止 JSON 数字、布尔值等类型被静默转换为业务字符串。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictTextFields() {
        // mapper：JSON 参数映射器或当前实体的数据访问接口。
        // builder：当前组件或请求的参数构建器。
        // 仅关闭标量强制转换仍不足以阻止数字、布尔值转成字符串，需要单独收紧字符串规则。
        return builder ->
                builder.postConfigurer(
                        mapper ->
                                mapper.coercionConfigFor(LogicalType.Textual)
                                        .setCoercion(
                                                CoercionInputShape.Integer, CoercionAction.Fail)
                                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                                        .setCoercion(
                                                CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
